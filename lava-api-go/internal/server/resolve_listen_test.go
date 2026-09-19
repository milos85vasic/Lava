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

// TestResolveListen_DynamicPort_ReturnsRealConnectableListener is the
// load-bearing anti-bluff test for LAVA_API_LISTEN=":0". It exercises the
// REAL server.ResolveListen function (which itself calls the REAL
// digital.vasic.containers/pkg/network.ListenEphemeral — no fake, no mock of
// the system under test per the Anti-Bluff Pact) and proves the returned
// port is not merely a plausible int: it dials the port for real.
func TestResolveListen_DynamicPort_ReturnsRealConnectableListener(t *testing.T) {
	resolved, ln, dynamic, err := server.ResolveListen(":0")
	if err != nil {
		t.Fatalf("ResolveListen(\":0\") = _, _, _, %v; want nil error", err)
	}
	if ln == nil {
		t.Fatal("tcpListener = nil; want a real, bound listener")
	}
	t.Cleanup(func() { _ = ln.Close() })

	if !dynamic {
		t.Errorf("dynamic = false; want true for a \":0\" request")
	}

	host, portStr, err := net.SplitHostPort(resolved)
	if err != nil {
		t.Fatalf("resolved address %q did not parse as host:port: %v", resolved, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("resolved port %q is not numeric: %v", portStr, err)
	}
	if port == 0 {
		t.Fatalf("resolved port is still 0 — the literal \":0\" request was never actually resolved")
	}
	if port < 1 || port > 65535 {
		t.Fatalf("resolved port %d out of valid range", port)
	}

	// Prove the listener is genuinely bound and connectable RIGHT NOW —
	// not just that a plausible-looking address string came back. This is
	// the discriminator between "the wiring compiled" and "the feature
	// works", per this project's Anti-Bluff Pact (Sixth Law clause 3:
	// primary assertion on user-visible / dial-able state).
	dialAddr := net.JoinHostPort(host, portStr)
	if host == "" {
		dialAddr = net.JoinHostPort("127.0.0.1", portStr)
	}
	conn, dialErr := net.Dial("tcp", dialAddr)
	if dialErr != nil {
		t.Fatalf("expected to be able to dial the port ResolveListen just bound (%s): %v", dialAddr, dialErr)
	}
	_ = conn.Close()

	// Also verify the returned net.Listener IS the same socket: closing it
	// must make the port unreachable again (proves ResolveListen returned
	// the live listener, not a listener it already released).
	if err := ln.Close(); err != nil {
		t.Fatalf("Close() on the resolved listener: %v", err)
	}
	if conn, err := net.DialTimeout("tcp", dialAddr, 200*time.Millisecond); err == nil {
		_ = conn.Close()
		t.Fatalf("port %s still accepting connections after ResolveListen's listener was closed", dialAddr)
	}
}

// TestResolveListen_DynamicPort_ConsecutiveCallsReturnDifferentPorts proves
// ResolveListen keeps the listener open rather than allocate-then-release
// (the classic TOCTOU shape network.ListenEphemeral exists to avoid) — two
// concurrent dynamic resolutions must never collide.
func TestResolveListen_DynamicPort_ConsecutiveCallsReturnDifferentPorts(t *testing.T) {
	resolved1, ln1, _, err := server.ResolveListen("127.0.0.1:0")
	if err != nil {
		t.Fatalf("first ResolveListen: %v", err)
	}
	t.Cleanup(func() { _ = ln1.Close() })

	resolved2, ln2, _, err := server.ResolveListen("127.0.0.1:0")
	if err != nil {
		t.Fatalf("second ResolveListen: %v", err)
	}
	t.Cleanup(func() { _ = ln2.Close() })

	if resolved1 == resolved2 {
		t.Fatalf("two live dynamic resolutions returned the same address %q — TOCTOU regression", resolved1)
	}

	for _, addr := range []string{resolved1, resolved2} {
		conn, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatalf("dial %s: %v", addr, err)
		}
		_ = conn.Close()
	}
}

// TestResolveListen_FixedPort_PreservesExistingBehavior is the regression
// guard for the "MUST preserve EXISTING behavior exactly" requirement: a
// fixed address (not port 0) must come back completely unchanged, and the
// returned listener must actually be bound to that exact address.
func TestResolveListen_FixedPort_PreservesExistingBehavior(t *testing.T) {
	// Pick a real free port first (host-assigned), then ask ResolveListen
	// for that EXACT fixed address — this is what a real deployment does
	// when LAVA_API_LISTEN is a literal, non-zero port such as ":8443".
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("probe listen: %v", err)
	}
	fixedAddr := probe.Addr().String()
	if err := probe.Close(); err != nil {
		t.Fatalf("probe close: %v", err)
	}

	resolved, ln, dynamic, err := server.ResolveListen(fixedAddr)
	if err != nil {
		t.Fatalf("ResolveListen(%q) = _, _, _, %v; want nil error", fixedAddr, err)
	}
	t.Cleanup(func() { _ = ln.Close() })

	if dynamic {
		t.Errorf("dynamic = true for a fixed address %q; want false", fixedAddr)
	}
	if resolved != fixedAddr {
		t.Errorf("resolved = %q; want unchanged %q for fixed-port mode", resolved, fixedAddr)
	}

	conn, err := net.Dial("tcp", fixedAddr)
	if err != nil {
		t.Fatalf("dial fixed address %s: %v", fixedAddr, err)
	}
	_ = conn.Close()
}

// TestResolveListen_FixedPort_CollisionIsRefused is the direct regression
// test for the forensic anchor that started this session: a fixed address
// already held by an unrelated process/listener MUST cause ResolveListen to
// refuse (return an error) rather than silently proceed — the entire point
// of confirming the bind BEFORE mDNS/Alt-Svc/logging depend on it.
func TestResolveListen_FixedPort_CollisionIsRefused(t *testing.T) {
	holder, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("holder listen: %v", err)
	}
	defer holder.Close()
	collidingAddr := holder.Addr().String()

	_, ln, _, err := server.ResolveListen(collidingAddr)
	if err == nil {
		if ln != nil {
			_ = ln.Close()
		}
		t.Fatalf("ResolveListen(%q) succeeded despite an already-bound holder on that exact address; want a bind-collision error", collidingAddr)
	}
}

// TestServerStartAndServeHTTP3_WithDynamicPort is the end-to-end anti-bluff
// Challenge: it runs the ENTIRE dynamic-port + confirmed-bind + reused-
// listener path through server.New/Start exactly as cmd/lava-api-go's run()
// does, then drives a REAL HTTP/3 client request against the resolved
// address and asserts on the response body — the same discipline as the
// existing TestServerStartAndServeHTTP3, but through the dynamic-port
// entry point instead of a pre-chosen fixed port.
func TestServerStartAndServeHTTP3_WithDynamicPort(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/_self_test", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	tlsConf, certPEM := selfSignedTLSForResolveListenTest(t)

	// Use an explicit loopback host ("127.0.0.1:0") rather than the bare
	// wildcard (":0") so the resolved address is directly usable as an
	// HTTP(S) client URL host below — a wildcard-bound "" host resolves to
	// e.g. ":45863", which is a valid net.Listen address but not a dialable
	// URL host. The wildcard-bind path is already covered by
	// TestResolveListen_DynamicPort_ReturnsRealConnectableListener above.
	resolvedListen, tcpListener, dynamic, err := server.ResolveListen("127.0.0.1:0")
	if err != nil {
		t.Fatalf("ResolveListen: %v", err)
	}
	if !dynamic {
		t.Fatalf("dynamic = false; want true")
	}

	metricsPort := freeTCPPortForResolveListenTest(t)
	metricsAddr := "127.0.0.1:" + strconv.Itoa(metricsPort)

	srv, err := server.New(server.Config{
		Listen:        resolvedListen,
		MetricsListen: metricsAddr,
		Engine:        r,
		MetricsHandler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte("# metrics ok\n"))
		}),
		TLSConfig: tlsConf,
		Listener:  tcpListener,
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

	// Wait for the UDP (HTTP/3) listener to actually come up on the
	// dynamically-resolved address.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, dialErr := net.Dial("udp", resolvedListen)
		if dialErr == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	cli := &http.Client{
		Transport: &http3.Transport{TLSClientConfig: clientTLSForResolveListenTest(certPEM)},
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
	if !strings.HasPrefix(resp.Proto, "HTTP/3") {
		t.Errorf("proto=%q want HTTP/3.x", resp.Proto)
	}

	// Also prove the SAME pre-bound TCP listener is what is actually
	// serving HTTP/2 — dial it over plain TCP+TLS h2 and confirm the
	// response round-trips too. This is the load-bearing proof that
	// Start() reused tcpListener via ServeTLS rather than telling the
	// stdlib to bind a fresh listener from an address string.
	h2Cli := &http.Client{
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: false,
				RootCAs:            clientTLSForResolveListenTest(certPEM).RootCAs,
				ServerName:         "localhost",
			},
		},
		Timeout: 3 * time.Second,
	}
	resp2, err := h2Cli.Get("https://" + srv.Addr() + "/_self_test")
	if err != nil {
		t.Fatalf("h2 get: %v", err)
	}
	defer resp2.Body.Close()
	body2, err := io.ReadAll(resp2.Body)
	if err != nil {
		t.Fatalf("read h2 body: %v", err)
	}
	if string(body2) != "ok" {
		t.Fatalf("h2 body=%q want ok", string(body2))
	}
}

func freeTCPPortForResolveListenTest(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen tcp: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}

func selfSignedTLSForResolveListenTest(t *testing.T) (*tls.Config, []byte) {
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

func clientTLSForResolveListenTest(certBytes []byte) *tls.Config {
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
