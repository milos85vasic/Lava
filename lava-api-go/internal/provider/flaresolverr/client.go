// Package flaresolverr is a minimal Go client for a FlareSolverr sidecar
// (https://github.com/FlareSolverr/FlareSolverr) — a headless-Chromium proxy
// that solves Cloudflare "Just a moment" challenges and returns the solved HTML.
//
// It is the seam Phase-2 curated providers (Cloudflare-gated trackers such as
// 1337x) use to fetch a page that a plain net/http GET cannot reach (403 +
// cf-mitigated: challenge). Unlike the Phase-1 curated providers (anonymous JSON
// APIs, zero external dependency), a provider built on this client requires a
// running FlareSolverr sidecar — so such providers MUST be registered only when
// a FlareSolverr endpoint is configured (§6.E honesty: never advertise a
// provider that cannot work).
package flaresolverr

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/httpx"
)

// DefaultMaxTimeout is the per-request solve budget handed to FlareSolverr (ms).
// Cloudflare challenge solves routinely take 5-15s; 60s leaves headroom.
const DefaultMaxTimeout = 60000

// DefaultTimeout bounds the whole HTTP call to the sidecar (solve budget + slack).
const DefaultTimeout = 90 * time.Second

// maxAttempts + retryBackoff bound a small retry on TRANSIENT sidecar failures
// (a network/timeout error from http.Do, or a sidecar 5xx). A single transient
// blip reaching the FlareSolverr sidecar otherwise surfaces to the user as a
// failed CF-gated fetch. maxAttempts is deliberately MODEST (2) because each
// attempt already carries the long DefaultTimeout (90s) + the sidecar's own
// DefaultMaxTimeout solve budget — a larger count would risk a multi-minute
// stall. The whole retry budget remains bounded by the caller's ctx (the backoff
// aborts early when ctx is cancelled/expired). Terminal outcomes (sidecar 4xx,
// decode failure, status != ok, upstream non-2xx, empty body → all ErrNotSolved)
// are NEVER retried — retrying them would only waste the (expensive) budget.
const (
	maxAttempts  = 2
	retryBackoff = 500 * time.Millisecond
)

// solvePath is the FlareSolverr v1 command endpoint.
const solvePath = "/v1"

// ErrNotSolved is returned when FlareSolverr replies but did not deliver a
// usable solved page (non-ok status, or an upstream non-200).
var ErrNotSolved = errors.New("flaresolverr: challenge not solved")

// Client talks to a FlareSolverr sidecar. The base URL + *http.Client are
// injectable so tests drive it against an httptest.Server.
type Client struct {
	baseURL string
	http    *http.Client
}

// NewClient returns a Client for the given FlareSolverr base URL (e.g.
// "http://flaresolverr:8191"). A trailing slash is trimmed.
func NewClient(baseURL string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		// Transport carries the configurable outbound proxy (loopback-exempt,
		// so a localhost FlareSolverr sidecar is reached directly) — internal/httpx.
		http: &http.Client{Timeout: DefaultTimeout, Transport: httpx.NewTransport()},
	}
}

// cmdRequest is the FlareSolverr request body. Only request.get is used.
type cmdRequest struct {
	Cmd        string `json:"cmd"`
	URL        string `json:"url"`
	MaxTimeout int    `json:"maxTimeout"`
}

// cmdResponse is the FlareSolverr reply envelope.
type cmdResponse struct {
	Status   string `json:"status"`
	Message  string `json:"message"`
	Solution struct {
		URL       string `json:"url"`
		Status    int    `json:"status"`
		Response  string `json:"response"`
		UserAgent string `json:"userAgent"`
	} `json:"solution"`
}

// Solution is the user-visible result of a solve: the final URL, the upstream
// HTTP status, and the solved HTML body.
type Solution struct {
	URL       string
	Status    int
	HTML      string
	UserAgent string
}

// Get asks FlareSolverr to fetch targetURL through a real browser (solving any
// Cloudflare challenge) and returns the solved HTML. It errors with ErrNotSolved
// when FlareSolverr reports a non-ok status OR the upstream returned a non-2xx
// (so a still-challenged page is never surfaced as a real result — anti-bluff).
func (c *Client) Get(ctx context.Context, targetURL string) (*Solution, error) {
	if c.baseURL == "" {
		return nil, fmt.Errorf("flaresolverr: no sidecar configured: %w", ErrNotSolved)
	}
	body, err := json.Marshal(cmdRequest{Cmd: "request.get", URL: targetURL, MaxTimeout: DefaultMaxTimeout})
	if err != nil {
		return nil, fmt.Errorf("flaresolverr: marshal: %w", err)
	}

	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		sol, transient, err := c.getOnce(ctx, targetURL, body)
		if err == nil {
			return sol, nil
		}
		lastErr = err
		if !transient || attempt == maxAttempts {
			return nil, err
		}
		select {
		case <-time.After(retryBackoff):
			// next attempt
		case <-ctx.Done():
			return nil, lastErr
		}
	}
	return nil, lastErr
}

// getOnce performs a single solve round-trip. The bool reports whether the error
// is transient (worth retrying): true for a network/timeout error from http.Do
// or a sidecar 5xx; false for terminal outcomes (sidecar 4xx, decode failure,
// status != ok, upstream non-2xx, empty body — all surfaced as ErrNotSolved) and
// on success.
func (c *Client) getOnce(ctx context.Context, targetURL string, body []byte) (*Solution, bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+solvePath, bytes.NewReader(body))
	if err != nil {
		return nil, false, fmt.Errorf("flaresolverr: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient.
		return nil, true, fmt.Errorf("flaresolverr: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		// A sidecar 5xx is transient (sidecar overloaded/restarting); other
		// non-200 sidecar statuses (4xx) are terminal.
		transient := resp.StatusCode >= 500
		return nil, transient, fmt.Errorf("flaresolverr: sidecar HTTP %d: %w", resp.StatusCode, ErrNotSolved)
	}

	var out cmdResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, false, fmt.Errorf("flaresolverr: decode: %w", err)
	}
	if !strings.EqualFold(out.Status, "ok") {
		return nil, false, fmt.Errorf("flaresolverr: status %q (%s): %w", out.Status, out.Message, ErrNotSolved)
	}
	// A solved-but-still-challenged or upstream-error page is NOT a usable result.
	if out.Solution.Status < 200 || out.Solution.Status >= 300 {
		return nil, false, fmt.Errorf("flaresolverr: upstream HTTP %d: %w", out.Solution.Status, ErrNotSolved)
	}
	if strings.TrimSpace(out.Solution.Response) == "" {
		return nil, false, fmt.Errorf("flaresolverr: empty solved body: %w", ErrNotSolved)
	}

	return &Solution{
		URL:       out.Solution.URL,
		Status:    out.Solution.Status,
		HTML:      out.Solution.Response,
		UserAgent: out.Solution.UserAgent,
	}, false, nil
}

// Health probes the sidecar with a trivial solve against a known-fast,
// non-challenged URL; used to decide whether to register CF providers.
func (c *Client) Health(ctx context.Context, probeURL string) error {
	_, err := c.Get(ctx, probeURL)
	return err
}
