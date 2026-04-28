// Package rutracker is the Lava-domain rutracker.org scraper. It wraps an
// HTTP client with a circuit breaker (Submodules/Recovery), forwards the
// auth cookie produced by internal/auth.UpstreamCookie, and exposes typed
// helpers each route handler invokes.
//
// This package is Lava-domain by spec §5: it knows the rutracker URL shape
// and the cookie-forwarding semantics used by the 13 routes. Generic
// circuit-breaker plumbing lives in Submodules/Recovery and is consumed
// here as thin glue (Decoupled Reusable Architecture rule).
package rutracker

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"digital.vasic.recovery/pkg/breaker"
)

// ErrCircuitOpen is returned when the breaker is in OPEN state and the
// request was not attempted. Exposed as a sentinel so callers can map it
// to HTTP 503 instead of the generic "upstream error" path.
var ErrCircuitOpen = errors.New("rutracker: circuit breaker OPEN")

// Client is the rutracker.org HTTP client wrapped with a circuit breaker.
type Client struct {
	base    string
	http    *http.Client
	breaker *breaker.CircuitBreaker
}

// NewClient returns a Client whose breaker trips after 5 consecutive
// failures and stays open for 10s before probing half-open. The HTTP
// client uses a 30s timeout — large enough for paginated forum pages,
// small enough that an unresponsive upstream cannot stall a request
// indefinitely.
//
// The breaker name is "rutracker" so observability/metrics can scope by
// name when multiple breakers exist in the same process.
func NewClient(base string) *Client {
	return &Client{
		base: base,
		http: &http.Client{Timeout: 30 * time.Second},
		breaker: breaker.NewCircuitBreaker(breaker.CircuitBreakerConfig{
			Name:         "rutracker",
			MaxFailures:  5,
			ResetTimeout: 10 * time.Second,
		}),
	}
}

// Fetch performs a GET against base+path with the given cookie value
// (may be empty for anonymous requests). It returns the response body
// bytes and the upstream status code. Transient errors (network I/O,
// 5xx upstream responses) count as breaker-relevant failures.
func (c *Client) Fetch(ctx context.Context, path, cookie string) ([]byte, int, error) {
	var (
		body   []byte
		status int
	)
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
		if err != nil {
			return err
		}
		if cookie != "" {
			req.Header.Set("Cookie", cookie)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			return err
		}
		defer func() { _ = resp.Body.Close() }()
		b, err := io.ReadAll(resp.Body)
		if err != nil {
			return err
		}
		body = b
		status = resp.StatusCode
		// Treat 5xx as breaker-relevant errors: a flaky upstream that
		// returns 502/503/504 should trip the breaker just like a TCP-
		// level failure would.
		if resp.StatusCode >= 500 {
			return fmt.Errorf("rutracker upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, err
	}
	return body, status, nil
}
