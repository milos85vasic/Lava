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
	"net/url"
	"strings"
	"time"

	"digital.vasic.recovery/pkg/breaker"
)

// ErrCircuitOpen is returned when the breaker is in OPEN state and the
// request was not attempted. Exposed as a sentinel so callers can map it
// to HTTP 503 instead of the generic "upstream error" path.
var ErrCircuitOpen = errors.New("rutracker: circuit breaker OPEN")

// ErrUnauthorized is returned by the comment-posting / favorite mutating
// flows when the supplied auth cookie is missing, the upstream main page
// no longer reports a logged-in user, or the rotating form_token is not
// present. Phase 7 handlers map this to HTTP 401.
//
// Ports the Kotlin `Unauthorized` exception thrown by VerifyTokenUseCase,
// VerifyAuthorisedUseCase and WithFormTokenUseCase.
var ErrUnauthorized = errors.New("rutracker: unauthorized")

// ErrNoData is returned by Login when the rutracker login response carries
// neither a Set-Cookie token nor the login-form HTML — i.e. the upstream
// is reachable but said something the Kotlin LoginUseCase doesn't know how
// to interpret. Maps to Kotlin's `throw NoData` branch in LoginUseCase.
var ErrNoData = errors.New("rutracker: login response contained neither token nor login form")

// ErrUnknown is returned by Login when the rutracker login response carries
// the login form (so we know we got there) but the embedded captcha cannot
// be parsed AND the response does not contain "неверный пароль". Maps to
// Kotlin's `throw Unknown` branch in LoginUseCase.
var ErrUnknown = errors.New("rutracker: login form present but neither captcha parseable nor wrong-credits indicated")

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

// FetchWithHeaders behaves like Fetch but additionally returns the upstream
// response headers. Used by GET /download/{id} which forwards the
// rutracker.org Content-Disposition (filename) and Content-Type
// (application/x-bittorrent) verbatim to the API client. Mirrors Fetch's
// breaker semantics: 5xx responses still trip the breaker, transient I/O
// errors still count as breaker-relevant failures.
func (c *Client) FetchWithHeaders(ctx context.Context, path, cookie string) ([]byte, int, http.Header, error) {
	var (
		body    []byte
		status  int
		headers http.Header
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
		headers = resp.Header.Clone()
		// Treat 5xx as breaker-relevant errors — same policy as Fetch.
		if resp.StatusCode >= 500 {
			return fmt.Errorf("rutracker upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, nil, err
	}
	return body, status, headers, nil
}

// PostFormWithHeaders behaves like PostForm but additionally returns the
// upstream response headers. Used by POST /login.php which needs the
// Set-Cookie headers (the auth token) AND the response body (the login
// form HTML if the credentials were rejected). Mirrors PostForm's
// breaker semantics: 5xx responses still trip the breaker, transient I/O
// errors still count as breaker-relevant failures.
func (c *Client) PostFormWithHeaders(ctx context.Context, path string, form url.Values, cookie string) ([]byte, int, http.Header, error) {
	var (
		body    []byte
		status  int
		headers http.Header
	)
	encoded := form.Encode()
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+path, strings.NewReader(encoded))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
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
		headers = resp.Header.Clone()
		// Treat 5xx as breaker-relevant errors — same policy as PostForm.
		if resp.StatusCode >= 500 {
			return fmt.Errorf("rutracker upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, nil, err
	}
	return body, status, headers, nil
}

// GetURL fetches an absolute URL (NOT prefixed with c.base) wrapped by the
// same circuit breaker as Fetch. Used for resources rutracker hosts on
// auxiliary domains — most notably the captcha images on static.t-ru.org
// referenced from the login form. Cookie may be empty (captcha images
// are public per rutracker's design).
func (c *Client) GetURL(ctx context.Context, fullURL, cookie string) ([]byte, int, http.Header, error) {
	var (
		body    []byte
		status  int
		headers http.Header
	)
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, fullURL, nil)
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
		headers = resp.Header.Clone()
		if resp.StatusCode >= 500 {
			return fmt.Errorf("rutracker upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, nil, err
	}
	return body, status, headers, nil
}

// PostForm performs a POST against base+path with the given form values
// and cookie value (may be empty). Behaves like Fetch — wrapped by the
// same circuit breaker, 5xx still trips the breaker, transient I/O
// errors still count as breaker-relevant failures.
//
// The Content-Type is fixed to application/x-www-form-urlencoded — the
// rutracker.org POST endpoints (postMessage, addBookmark, removeBookmark)
// all consume url-encoded form data via $_POST. Mirrors Ktor's
// `httpClient.submitForm(... formData { ... })` call shape.
func (c *Client) PostForm(ctx context.Context, path string, form url.Values, cookie string) ([]byte, int, error) {
	var (
		body   []byte
		status int
	)
	encoded := form.Encode()
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+path, strings.NewReader(encoded))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
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
		// Treat 5xx as breaker-relevant errors for parity with Fetch:
		// a flaky upstream that returns 502/503/504 should trip the
		// breaker just like a TCP-level failure would.
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
