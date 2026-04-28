package main

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
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"digital.vasic.http3/pkg/server"
)

// generateTestTLSConfig builds a self-signed TLS config valid for
// "localhost", 127.0.0.1, and ::1 with the "h3" NextProto pre-populated.
// We can't import digital.vasic.http3/internal/testcert from this
// repository (Go-internal-package rule), so we duplicate the minimal
// machinery here. This is acceptable per the test-glue carve-out:
// production code does NOT do this, only test code.
func generateTestTLSConfig(t *testing.T) *tls.Config {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := &x509.Certificate{
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
	derBytes, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes})
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
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
	}
}

// freeUDPPort grabs a free UDP port (HTTP/3 lives on UDP, not TCP).
func freeUDPPort(t *testing.T) int {
	t.Helper()
	addr, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	port := conn.LocalAddr().(*net.UDPAddr).Port
	conn.Close()
	return port
}

// startH3 boots a real HTTP/3 server backed by digital.vasic.http3 and
// returns its base URL ("https://127.0.0.1:<port>"). t.Cleanup handles
// shutdown.
func startH3(t *testing.T, h http.Handler) string {
	t.Helper()
	port := freeUDPPort(t)
	addr := "127.0.0.1:" + strconv.Itoa(port)
	srv, err := server.New(server.Config{
		Addr:    addr,
		Handler: h,
		TLSConf: generateTestTLSConfig(t),
	})
	if err != nil {
		t.Fatalf("server.New: %v", err)
	}
	go func() { _ = srv.Start() }()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	})
	// Wait for the UDP listener to bind.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.Dial("udp", addr)
		if err == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	return "https://" + addr
}

// TestProbe_HappyPath_Returns200 spins up a real HTTP/3 server that
// returns 200 on /health and asserts probe round-trips the status. The
// test exercises the same HTTP/3 transport that the production
// healthprobe binary uses against the production lava-api-go server in
// Docker — Sixth Law clause 1.
func TestProbe_HappyPath_Returns200(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"status":"ok"}`)
	})
	base := startH3(t, mux)

	status, err := probe(base+"/health", true, 5*time.Second)
	if err != nil {
		t.Fatalf("probe: %v", err)
	}
	if status != http.StatusOK {
		t.Errorf("probe status = %d, want %d", status, http.StatusOK)
	}
}

// TestProbe_404_ReturnsStatusNotOK asserts probe surfaces a non-2xx
// status from the server. Falsifiability target: mutating probe() to
// always return 200 must cause this test to fail (because the server
// here actually returns 404).
func TestProbe_404_ReturnsStatusNotOK(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "not found", http.StatusNotFound)
	})
	base := startH3(t, mux)

	status, err := probe(base+"/health", true, 5*time.Second)
	if err != nil {
		t.Fatalf("probe should not error on 404 response, got: %v", err)
	}
	if status != http.StatusNotFound {
		t.Errorf("probe status = %d, want %d", status, http.StatusNotFound)
	}
	if status >= 200 && status < 300 {
		t.Errorf("probe status %d should NOT be classified as healthy", status)
	}
}

// TestProbe_NetworkError_ReturnsError probes an unreachable address and
// asserts a transport error is surfaced. This exercises the err branch
// of probe — the branch main() uses to exit 1 on transport failure.
func TestProbe_NetworkError_ReturnsError(t *testing.T) {
	// Pick a port that's almost certainly closed. Port 1 is a privileged
	// port that no normal user-space process binds; on most hosts dialling
	// it produces a fast connection-refused / timeout.
	status, err := probe("https://127.0.0.1:1/health", true, 1500*time.Millisecond)
	if err == nil {
		t.Fatalf("probe of unreachable URL succeeded with status %d; expected error", status)
	}
	if status != 0 {
		t.Errorf("on transport error probe should return 0, got %d", status)
	}
	// Sanity: the error text should mention the URL we asked about, so
	// operators can diagnose Docker HEALTHCHECK failures from container
	// logs without needing source access.
	if !strings.Contains(err.Error(), "127.0.0.1:1") {
		t.Errorf("error %q should mention the unreachable URL", err)
	}
}
