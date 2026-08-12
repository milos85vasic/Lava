// Package rutracker is the Lava-domain rutracker.org scraper. It wraps an
// HTTP client with a circuit breaker (submodules/recovery), forwards the
// auth cookie produced by internal/auth.UpstreamCookie, and exposes typed
// helpers each route handler invokes.
//
// This package is Lava-domain by spec §5: it knows the rutracker URL shape
// and the cookie-forwarding semantics used by the 13 routes. Generic
// circuit-breaker plumbing lives in submodules/recovery and is consumed
// here as thin glue (Decoupled Reusable Architecture rule).
package rutracker

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/httpx"
	"digital.vasic.recovery/pkg/breaker"
	"golang.org/x/net/html/charset"
)

// ErrCloudflareChallenge is returned when rutracker.org's Cloudflare edge
// answers a request with an active bot-mitigation challenge (Turnstile /
// "Managed Challenge") instead of the real page. This is an EXTERNAL,
// network-reputation-level block — no amount of header/cookie tuning on
// this HTTP client can pass a genuine JS-execution challenge, since Go's
// net/http (like curl) does not run JavaScript. Distinguishing this from
// a generic upstream error matters because the two have completely
// different remediations: a code bug is fixed here; a Cloudflare
// challenge requires either a JS-capable client (out of scope for this
// package) or waiting out / rotating past whatever triggered the
// elevated challenge posture for this egress IP.
//
// Forensic anchor (2026-08-12): live reproduction against the real
// rutracker.org/forum/login.php from this service's actual egress IP
// returned HTTP 403 with response header `cf-mitigated: challenge` and a
// Content-Security-Policy referencing https://challenges.cloudflare.com
// (Cloudflare Turnstile). Before this fix, that response fell through to
// the generic "neither token nor login form" branch, which the caller
// classified as ErrNoData → an unhelpful 502 with body {} — no signal at
// all that a Cloudflare challenge was the actual cause. A prior 2026-05-12
// mitigation (see docs/issues/fixed/BUGFIXES.md "rutracker-cloudflare-
// mitigation") solved a SOFTER form of Cloudflare bot detection (missing
// browser headers/cookies) in the separate Kotlin client — that same
// mitigation was verified NOT sufficient against this harder JS-challenge
// posture (tested live with proper headers + a warmed cookie jar; still
// 403 cf-mitigated:challenge), so this package does not claim to defeat
// the challenge — only to SURFACE it honestly instead of masking it as a
// generic parse failure.
var ErrCloudflareChallenge = errors.New("rutracker: blocked by Cloudflare bot-challenge (JS execution required); this is an external network/reputation block, not a Lava defect")

// isCloudflareChallenge reports whether an upstream response is a
// Cloudflare bot-mitigation challenge rather than a real rutracker.org
// page. The `cf-mitigated: challenge` response header is Cloudflare's own,
// unambiguous signal (see the ErrCloudflareChallenge forensic anchor) —
// checked in preference to guessing from status code alone, since a plain
// 403 can also be a legitimate rutracker.org access-denied response.
func isCloudflareChallenge(status int, headers http.Header) bool {
	if status != http.StatusForbidden {
		return false
	}
	return strings.EqualFold(headers.Get("cf-mitigated"), "challenge")
}

// setBrowserHeaders sets a realistic browser fingerprint on an outgoing
// rutracker.org request. Before this fix, requests carried NO User-Agent
// at all, so Go's http.Client sent the default "Go-http-client/1.1" — an
// unambiguous non-browser fingerprint that bot-detection systems key on
// directly. Matches the browser-class headers already proven to help
// against rutracker's SOFTER Cloudflare posture in the separate Kotlin
// client (docs/issues/fixed/BUGFIXES.md "rutracker-cloudflare-
// mitigation", 2026-05-12) — Chrome-124-class UA, Accept, Accept-Language.
func setBrowserHeaders(req *http.Request) {
	req.Header.Set("User-Agent",
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "+
			"(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
	req.Header.Set("Accept",
		"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
}

// newBrowserRequest is http.NewRequestWithContext plus setBrowserHeaders,
// so every outgoing rutracker.org request carries a realistic fingerprint
// by construction — a caller cannot forget the headers by using this
// instead of the raw stdlib constructor directly.
func newBrowserRequest(ctx context.Context, method, url string, body io.Reader) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, url, body)
	if err != nil {
		return nil, err
	}
	setBrowserHeaders(req)
	return req, nil
}

// readBodyDecoded reads resp.Body and transcodes it from the upstream's
// declared charset to UTF-8. rutracker.org serves
// `text/html; charset=Windows-1251` (and a `<meta charset="Windows-1251">`
// inside the HTML); passing the raw bytes to goquery — which assumes
// UTF-8 — produces mojibake on Cyrillic content. This was caught by the
// Phase 14 cross-backend parity test against the Kotlin Ktor proxy
// (which decodes via its HttpClient's `bodyAsText()` charset path):
//
//	ktor: "VPN-сервисы"   (correctly decoded UTF-8 of Russian text)
//	go:   "VPN-�������"   (windows-1251 bytes interpreted as UTF-8)
//
// charset.NewReader sniffs the Content-Type header AND any
// <meta charset="..."> tag before returning a UTF-8-emitting io.Reader.
// For non-HTML responses (e.g. /dl.php binary) the charset.NewReader
// passes bytes through unchanged.
func readBodyDecoded(resp *http.Response) ([]byte, error) {
	ct := resp.Header.Get("Content-Type")
	// Only transcode text-ish payloads. Binary endpoints (notably
	// /dl.php's application/x-bittorrent) MUST pass through verbatim —
	// running charset detection on a torrent file would either be a
	// no-op (best case) or corrupt the wire bytes (worst case).
	mt := strings.ToLower(strings.TrimSpace(strings.SplitN(ct, ";", 2)[0]))
	textish := strings.HasPrefix(mt, "text/") || mt == "application/xhtml+xml" || mt == "application/xml" || mt == "application/json"
	if !textish {
		// Empty Content-Type, binary payload, or anything goquery wouldn't
		// parse: pass through. charset.NewReader on an empty body returns
		// EOF instead of (nil, nil), which would surface as a spurious
		// error to callers like /dl.php (404 path).
		return io.ReadAll(resp.Body)
	}
	// SP-3.5 (2026-04-29) forensic anchor #3: rutracker's POST /forum/login.php
	// successful response is a 302 with `content-type: text/html; charset=cp1251`
	// AND a zero-length body. charset.NewReader on an empty stream returns
	// EOF on the first Read — io.ReadAll then propagates that EOF up,
	// PostFormWithHeaders surfaces it through the breaker, login.go logs
	// `err=EOF` and the client gets 502 — even though the auth token IS
	// already present in the response headers (which the caller had no
	// chance to inspect because we errored out on body decode first).
	// Read the raw bytes first so we can short-circuit on empty body.
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		return raw, nil
	}
	r, err := charset.NewReader(bytes.NewReader(raw), ct)
	if err != nil {
		return nil, err
	}
	return io.ReadAll(r)
}

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
//
// SP-3.5 (2026-04-29) forensic anchor: the underlying Transport is
// pinned to IPv4 (`tcp4`). Real-device verification on the operator's
// home LAN showed that Cloudflare's IPv6 edge for rutracker.org happily
// completes the TLS handshake on POST /forum/login.php but then
// silently drops the request — bytes-received is zero, the call hangs
// until the client's 30s timeout fires, and the breaker counts the
// failure. The IPv4 edge serves the same request in ~150ms (verified
// 2026-04-29 with the operator's actual credentials → HTTP/2 302 +
// bb_session cookie). The host's `getent ahosts rutracker.org` returns
// IPv6 records first (preferred by Go's net.Dialer when both stacks
// exist), so without this pin the Login endpoint always 502'd on a
// dual-stack host even when the user supplied valid credentials —
// exactly the user-visible "Cannot sign in with valid credentials"
// symptom on real device on 2026-04-29.
//
// "tcp4" is forced ONLY for the rutracker upstream client, NOT the
// metrics/HTTP server side. The Android client and the operator's LAN
// machines stay free to talk to lava-api-go itself over either stack.
func NewClient(base string) *Client {
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
	}
	transport := &http.Transport{
		// Route upstream egress through the configurable outbound proxy
		// (LAVA_API_UPSTREAM_PROXY / *_PROXY env) so the operator can bypass a
		// datacenter egress block on rutracker.org. See internal/httpx.
		Proxy: httpx.Proxy,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// Force IPv4. See SP-3.5 forensic anchor in NewClient KDoc.
			if network == "tcp" || network == "tcp6" {
				network = "tcp4"
			}
			return dialer.DialContext(ctx, network, addr)
		},
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		ForceAttemptHTTP2:     true,
	}
	return &Client{
		base: base,
		http: &http.Client{
			Timeout:   30 * time.Second,
			Transport: transport,
			// Deliberately NO cookie Jar: this package threads cookies
			// manually via an explicit `cookie string` parameter on every
			// call (Login -> fetchUserProfile -> ...), a design forced by
			// CheckRedirect=ErrUseLastResponse below (the load-bearing
			// auth token arrives on a 302 response header, not a page body,
			// so redirects are never auto-followed). A cookie Jar was
			// tried during the 2026-08-12 Cloudflare-challenge
			// investigation to let a cf_clearance token persist across
			// calls, but it broke TestLogin_Success_FetchesProfile for
			// real: the Jar auto-attached previously-stored cookies
			// (e.g. bb_ssl) ALONGSIDE the manually-set Cookie header,
			// producing a duplicated/malformed Cookie value on the
			// profile-fetch request. Reverted — see
			// internal/rutracker/client_test.go for the regression this
			// would reintroduce if a Jar is added again without also
			// reconciling it against the manual cookie-threading design.
			//
			// SP-3.5 (2026-04-29) forensic anchor #2: rutracker's
			// successful POST /forum/login.php returns
			//   HTTP/2 302
			//   Location: /forum/index.php
			//   Set-Cookie: bb_session=<token>
			// The auth token is on the 302 — NOT on the redirected
			// /index.php page. Go's default http.Client.CheckRedirect
			// transparently follows redirects, so the Login flow
			// observed the redirected page (a login form for an
			// unauthenticated session because Go does not forward the
			// just-received Set-Cookie when following the redirect),
			// fell through ExtractLoginToken's "no token" branch,
			// then through "no wrong-credits sentinel", and finally
			// produced ErrUnknown → 502 — even though the credentials
			// were perfectly valid. Verified on real device 2026-04-29
			// with `curl -4 -i` showing the 302 + bb_session that the
			// Go client was throwing away.
			//
			// `http.ErrUseLastResponse` tells the client to surface
			// the 302 to our caller verbatim, which is what the
			// scraper's Login implementation already expects (see the
			// `ExtractLoginToken(headers)` call on the response of
			// PostFormWithHeaders — that headers map IS the original
			// 302's headers, including the load-bearing Set-Cookie).
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
		breaker: breaker.NewCircuitBreaker(breaker.CircuitBreakerConfig{
			Name:         "rutracker",
			MaxFailures:  5,
			ResetTimeout: 10 * time.Second,
		}),
	}
}

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (a network/timeout error from http.Do, or a 5xx status). The retry lives
// INSIDE the breaker's Execute closure so a single slow/flaky upstream is
// retried BEFORE it ever counts as a breaker failure. Without this, one
// transient timeout would tick the breaker's consecutive-failure counter, and
// 5 such transients (MaxFailures) would OPEN the breaker — locking the user out
// of rutracker for ~10s on what was a single slow request. Terminal errors
// (4xx, decode failures) are NEVER retried — they will not resolve on a retry
// and only burn the budget; they DO surface to the breaker as a single genuine
// failure. Mirrors the tokyotosho transient-vs-terminal classification. The
// budget is bounded by the caller's ctx — each backoff aborts early if ctx is
// cancelled/expired.
const (
	maxAttempts  = 3
	retryBackoff = 500 * time.Millisecond
)

// doWithRetry runs attempt up to maxAttempts times, retrying only while attempt
// reports the failure was transient. attempt returns (transient, err): err==nil
// means success (return immediately); transient==true with a non-nil err means
// "worth retrying"; transient==false means terminal (return the err now). The
// backoff between attempts is ctx-bounded.
//
// Because doWithRetry returns nil whenever a retry ultimately succeeds, the
// enclosing breaker.Execute sees success and does NOT increment its failure
// counter — a recovered transient does not move the breaker toward OPEN. Only a
// genuine (post-retry) failure returns a non-nil error to the breaker, so the
// breaker's failure-counting for real outages is unchanged.
func (c *Client) doWithRetry(ctx context.Context, attempt func() (transient bool, err error)) error {
	var lastErr error
	for i := 1; i <= maxAttempts; i++ {
		transient, err := attempt()
		if err == nil {
			return nil
		}
		lastErr = err
		if !transient || i == maxAttempts {
			return err
		}
		select {
		case <-time.After(retryBackoff):
			// next attempt
		case <-ctx.Done():
			return lastErr
		}
	}
	return lastErr
}

// Fetch performs a GET against base+path with the given cookie value
// (may be empty for anonymous requests). It returns the response body
// bytes and the upstream status code. Transient errors (network I/O,
// 5xx upstream responses) are retried up to maxAttempts before counting
// as breaker-relevant failures.
func (c *Client) Fetch(ctx context.Context, path, cookie string) ([]byte, int, error) {
	var (
		body   []byte
		status int
	)
	err := c.breaker.Execute(func() error {
		return c.doWithRetry(ctx, func() (bool, error) {
			req, err := newBrowserRequest(ctx, http.MethodGet, c.base+path, nil)
			if err != nil {
				return false, err
			}
			if cookie != "" {
				req.Header.Set("Cookie", cookie)
			}
			resp, err := c.http.Do(req)
			if err != nil {
				// Network/timeout — transient; retry before tripping the breaker.
				return true, err
			}
			defer func() { _ = resp.Body.Close() }()
			b, err := readBodyDecoded(resp)
			if err != nil {
				return false, err
			}
			body = b
			status = resp.StatusCode
			// Treat 5xx as breaker-relevant errors: a flaky upstream that
			// returns 502/503/504 should trip the breaker just like a TCP-
			// level failure would — but only after the bounded retry fails.
			if resp.StatusCode >= 500 {
				return true, fmt.Errorf("rutracker upstream %d", resp.StatusCode)
			}
			return false, nil
		})
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
		return c.doWithRetry(ctx, func() (bool, error) {
			req, err := newBrowserRequest(ctx, http.MethodGet, c.base+path, nil)
			if err != nil {
				return false, err
			}
			if cookie != "" {
				req.Header.Set("Cookie", cookie)
			}
			resp, err := c.http.Do(req)
			if err != nil {
				return true, err
			}
			defer func() { _ = resp.Body.Close() }()
			b, err := readBodyDecoded(resp)
			if err != nil {
				return false, err
			}
			body = b
			status = resp.StatusCode
			headers = resp.Header.Clone()
			// Treat 5xx as breaker-relevant errors — same policy as Fetch.
			if resp.StatusCode >= 500 {
				return true, fmt.Errorf("rutracker upstream %d", resp.StatusCode)
			}
			return false, nil
		})
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
		return c.doWithRetry(ctx, func() (bool, error) {
			req, err := newBrowserRequest(ctx, http.MethodPost, c.base+path, strings.NewReader(encoded))
			if err != nil {
				return false, err
			}
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			if cookie != "" {
				req.Header.Set("Cookie", cookie)
			}
			resp, err := c.http.Do(req)
			if err != nil {
				return true, err
			}
			defer func() { _ = resp.Body.Close() }()
			b, err := readBodyDecoded(resp)
			if err != nil {
				return false, err
			}
			body = b
			status = resp.StatusCode
			headers = resp.Header.Clone()
			// Treat 5xx as breaker-relevant errors — same policy as PostForm.
			if resp.StatusCode >= 500 {
				return true, fmt.Errorf("rutracker upstream %d", resp.StatusCode)
			}
			return false, nil
		})
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
		return c.doWithRetry(ctx, func() (bool, error) {
			req, err := newBrowserRequest(ctx, http.MethodGet, fullURL, nil)
			if err != nil {
				return false, err
			}
			if cookie != "" {
				req.Header.Set("Cookie", cookie)
			}
			resp, err := c.http.Do(req)
			if err != nil {
				return true, err
			}
			defer func() { _ = resp.Body.Close() }()
			b, err := readBodyDecoded(resp)
			if err != nil {
				return false, err
			}
			body = b
			status = resp.StatusCode
			headers = resp.Header.Clone()
			if resp.StatusCode >= 500 {
				return true, fmt.Errorf("rutracker upstream %d", resp.StatusCode)
			}
			return false, nil
		})
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
		return c.doWithRetry(ctx, func() (bool, error) {
			req, err := newBrowserRequest(ctx, http.MethodPost, c.base+path, strings.NewReader(encoded))
			if err != nil {
				return false, err
			}
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			if cookie != "" {
				req.Header.Set("Cookie", cookie)
			}
			resp, err := c.http.Do(req)
			if err != nil {
				return true, err
			}
			defer func() { _ = resp.Body.Close() }()
			b, err := readBodyDecoded(resp)
			if err != nil {
				return false, err
			}
			body = b
			status = resp.StatusCode
			// Treat 5xx as breaker-relevant errors for parity with Fetch:
			// a flaky upstream that returns 502/503/504 should trip the
			// breaker just like a TCP-level failure would.
			if resp.StatusCode >= 500 {
				return true, fmt.Errorf("rutracker upstream %d", resp.StatusCode)
			}
			return false, nil
		})
	})
	if err != nil {
		return nil, 0, err
	}
	return body, status, nil
}
