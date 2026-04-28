package server_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/quic-go/quic-go/http3"

	"digital.vasic.lava.apigo/internal/server"
)

// TestServerStartAndServeHTTP3 is the load-bearing integration test for
// internal/server. It exercises the full path:
//
//	HTTP/3 client → quic-go transport → Submodules/HTTP3 → gin.Engine.
//
// Sixth Law primary assertion: the response body and the negotiated
// protocol must both match, so a regression in route registration OR
// in the HTTP/3 wiring is caught.
func TestServerStartAndServeHTTP3(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/_self_test", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	tlsConf, certPEM := selfSignedTLS(t)

	publicPort := freeUDPPort(t)
	metricsPort := freeTCPPort(t)
	publicAddr := "127.0.0.1:" + strconv.Itoa(publicPort)
	metricsAddr := "127.0.0.1:" + strconv.Itoa(metricsPort)

	srv, err := server.New(server.Config{
		Listen:        publicAddr,
		MetricsListen: metricsAddr,
		Engine:        r,
		MetricsHandler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte("# metrics ok\n"))
		}),
		TLSConfig: tlsConf,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	startErrCh := make(chan error, 1)
	go func() { startErrCh <- srv.Start() }()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	})

	// Wait for the UDP listener to bind. Same pattern as
	// Submodules/HTTP3/pkg/server/integration_test.go::startServer.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.Dial("udp", publicAddr)
		if err == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	cli := &http.Client{
		Transport: &http3.Transport{TLSClientConfig: clientTLS(certPEM)},
		Timeout:   3 * time.Second,
	}
	defer func() {
		if rt, ok := cli.Transport.(*http3.Transport); ok {
			_ = rt.Close()
		}
	}()

	resp, err := cli.Get("https://" + srv.Addr() + "/_self_test")
	if err != nil {
		t.Fatalf("h3 get: %v", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if string(body) != "ok" {
		t.Fatalf("body=%q want ok", string(body))
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status=%d want 200", resp.StatusCode)
	}
	if !strings.HasPrefix(resp.Proto, "HTTP/3") {
		t.Errorf("proto=%q want HTTP/3.x", resp.Proto)
	}
}

// TestServerMetricsListenerServes200 verifies the dedicated localhost
// metrics listener actually serves the metrics handler with a 200
// response. The metrics handler is provided by the caller
// (cmd/lava-api-go wires obsmetrics.MetricsHandler); here we use a
// stub handler that emits a known body, drive a real GET via stdlib
// net/http (HTTP/1.1, plain text, NOT HTTP/3 — that's the public
// listener's job), and assert the body round-trips.
//
// Sixth Law primary assertion: body bytes — a regression that wires
// nil, the wrong handler, or the wrong port fails on the body read.
func TestServerMetricsListenerServes200(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/_unused", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	tlsConf, _ := selfSignedTLS(t)

	publicPort := freeUDPPort(t)
	metricsPort := freeTCPPort(t)
	publicAddr := "127.0.0.1:" + strconv.Itoa(publicPort)
	metricsAddr := "127.0.0.1:" + strconv.Itoa(metricsPort)

	const stubBody = "metrics-stub-OK"

	srv, err := server.New(server.Config{
		Listen:        publicAddr,
		MetricsListen: metricsAddr,
		Engine:        r,
		MetricsHandler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(stubBody))
		}),
		TLSConfig: tlsConf,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	go func() { _ = srv.Start() }()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	})

	// Wait for the TCP metrics listener to bind. Same shape as the
	// HTTP/3 wait loop above, but on TCP.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, derr := net.Dial("tcp", metricsAddr)
		if derr == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	if got := srv.MetricsAddr(); got != metricsAddr {
		t.Fatalf("MetricsAddr()=%q want %q", got, metricsAddr)
	}

	cli := &http.Client{Timeout: 2 * time.Second}
	resp, err := cli.Get("http://" + metricsAddr + "/metrics")
	if err != nil {
		t.Fatalf("metrics GET: %v", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status=%d want 200", resp.StatusCode)
	}
	if !strings.Contains(string(body), stubBody) {
		t.Fatalf("body=%q want substring %q", string(body), stubBody)
	}
}

// TestServerRejectsMissingTLSConfig sanity-checks the New() validation
// path. If the TLSConfig requirement is ever softened (it shouldn't —
// HTTP/3 mandates TLS 1.3) this test fails.
func TestServerRejectsMissingTLSConfig(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	_, err := server.New(server.Config{
		Listen:         ":0",
		MetricsListen:  "127.0.0.1:0",
		Engine:         r,
		MetricsHandler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {}),
		TLSConfig:      nil,
	})
	if err == nil {
		t.Fatal("expected error when TLSConfig is nil")
	}
}

// TestServerShutdownIsIdempotent verifies that calling Shutdown twice
// does not panic and the second call returns nil.
func TestServerShutdownIsIdempotent(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/", func(c *gin.Context) { c.Status(http.StatusOK) })

	tlsConf, _ := selfSignedTLS(t)
	publicPort := freeUDPPort(t)
	metricsPort := freeTCPPort(t)

	srv, err := server.New(server.Config{
		Listen:        "127.0.0.1:" + strconv.Itoa(publicPort),
		MetricsListen: "127.0.0.1:" + strconv.Itoa(metricsPort),
		Engine:        r,
		MetricsHandler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte("ok"))
		}),
		TLSConfig: tlsConf,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	go func() { _ = srv.Start() }()
	time.Sleep(150 * time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		t.Fatalf("first Shutdown: %v", err)
	}
	if err := srv.Shutdown(ctx); err != nil {
		t.Fatalf("second Shutdown: %v", err)
	}
}

// freeUDPPort returns a UDP port that the kernel just reported as
// free. The same port number is then bound by the http3 server.
// Window between this function returning and the server binding is
// small but non-zero; in practice the kernel rarely re-allocates a
// just-released port to another process.
func freeUDPPort(t *testing.T) int {
	t.Helper()
	addr, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("resolve udp: %v", err)
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	port := conn.LocalAddr().(*net.UDPAddr).Port
	conn.Close()
	return port
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen tcp: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}

// selfSignedTLS mints a fresh ECDSA P-256 cert valid for localhost,
// 127.0.0.1, ::1 and returns a *tls.Config plus the PEM-encoded cert
// bytes for the client root pool. Adapted from
// Submodules/HTTP3/internal/testcert/testcert.go (which is `internal`
// to that submodule and thus not importable from here per Go's
// internal-package rules).
func selfSignedTLS(t *testing.T) (*tls.Config, []byte) {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("ecdsa key: %v", err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "localhost"},
		NotBefore:             time.Now().Add(-time.Minute),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback},
	}
	derBytes, err := x509.CreateCertificate(rand.Reader, template, template, &priv.PublicKey, priv)
	if err != nil {
		t.Fatalf("create cert: %v", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes})
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		t.Fatalf("marshal ec priv: %v", err)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatalf("X509KeyPair: %v", err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"h3"},
	}, certPEM
}

// clientTLS returns a *tls.Config that trusts the supplied cert and
// will negotiate HTTP/3 ALPN. Used by the HTTP/3 test client.
func clientTLS(certBytes []byte) *tls.Config {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(certBytes) {
		panic("failed to parse self-signed cert PEM")
	}
	return &tls.Config{
		RootCAs:    pool,
		NextProtos: []string{"h3"},
		ServerName: "localhost",
	}
}

// Compile-time guard: keep gofmt+goimports satisfied with fmt usage so
// future edits adding %v don't churn the import block.
var _ = fmt.Sprint
