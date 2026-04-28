// Command healthprobe pings the lava-api-go server's /health endpoint
// over HTTP/3 and exits 0 on a 2xx response, non-zero otherwise.
//
// It is invoked by the Docker HEALTHCHECK directive (wired in Phase 9 /
// Task 9.2). Self-signed TLS certs are normal in LAN deployments, so
// -insecure defaults to true; flip it for a public deployment that
// terminates with real certs.
//
// The probe logic is factored into probe(url, insecure) so it can be
// exercised by unit tests without spawning a subprocess. main() is a
// trivial CLI shell over probe().
package main

import (
	"crypto/tls"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/quic-go/quic-go/http3"
)

const (
	defaultURL     = "https://localhost:8443/health"
	defaultTimeout = 5 * time.Second
)

func main() {
	url := flag.String("url", defaultURL, "URL of the /health endpoint (HTTP/3)")
	insecure := flag.Bool("insecure", true, "skip TLS verification (LAN self-signed certs)")
	timeout := flag.Duration("timeout", defaultTimeout, "request timeout")
	flag.Parse()

	status, err := probe(*url, *insecure, *timeout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "healthprobe: %v\n", err)
		os.Exit(1)
	}
	if status < 200 || status >= 300 {
		fmt.Fprintf(os.Stderr, "healthprobe: unhealthy status %d\n", status)
		os.Exit(1)
	}
	fmt.Printf("healthprobe: %s -> %d\n", *url, status)
}

// probe performs a single GET against url over HTTP/3 and returns the
// HTTP status code. On transport failure (DNS, dial, TLS, QUIC) it
// returns (0, err). On a successful round-trip it returns (status, nil)
// regardless of the status value — the caller decides whether the
// status counts as healthy.
func probe(url string, insecure bool, timeout time.Duration) (int, error) {
	rt := &http3.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: insecure, //nolint:gosec // LAN self-signed certs; -insecure is a deliberate operator flag.
			NextProtos:         []string{http3.NextProtoH3},
		},
	}
	defer rt.Close()
	cli := &http.Client{Transport: rt, Timeout: timeout}
	defer cli.CloseIdleConnections()

	resp, err := cli.Get(url)
	if err != nil {
		return 0, fmt.Errorf("GET %s: %w", url, err)
	}
	defer resp.Body.Close()
	return resp.StatusCode, nil
}
