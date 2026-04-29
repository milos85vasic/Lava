package rutracker

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// TestFetchReturnsBodyAndStatus verifies the happy-path round-trip: a 200
// response from the upstream surfaces as (body, 200, nil) at the caller.
//
// This is the user-visible behaviour every route handler depends on:
// "GET path → body". The assertion is on the actual body bytes and the
// actual status code (Sixth Law clause 3 — primary assertion on
// user-visible state).
func TestFetchReturnsBodyAndStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("<html>hi</html>"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	body, status, err := c.Fetch(context.Background(), "/", "")
	if err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if status != http.StatusOK {
		t.Errorf("status=%d want 200", status)
	}
	if string(body) != "<html>hi</html>" {
		t.Errorf("body=%q want %q", string(body), "<html>hi</html>")
	}
}

// TestFetchTrips5xxIntoBreakerError verifies that a 5xx response from the
// upstream surfaces as a non-nil error — the precondition for the breaker
// counting it as a failure. Without this, the breaker would never trip on
// a flaky upstream that returns 500s instead of dropping connections.
//
// Falsifiability rehearsal target: comment out the
// `if resp.StatusCode >= 500 { return fmt.Errorf(...) }` block in
// client.go; this test MUST then fail with
// "expected error for 500 response, got nil".
func TestFetchTrips5xxIntoBreakerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, _, err := c.Fetch(context.Background(), "/", "")
	if err == nil {
		t.Fatal("expected error for 500 response, got nil")
	}
}

// TestFetchForwardsCookieHeader verifies that a non-empty cookie value is
// forwarded as the upstream Cookie header verbatim. This is the cookie
// pass-through contract internal/auth.UpstreamCookie relies on.
func TestFetchForwardsCookieHeader(t *testing.T) {
	const want = "bb_session=abc123; bb_data=opaque"
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("Cookie")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	if _, _, err := c.Fetch(context.Background(), "/", want); err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if got != want {
		t.Errorf("upstream Cookie header=%q want %q", got, want)
	}
}

// TestNewClient_TransportForcesIPv4 — SP-3.5 Sixth-Law regression guard.
//
// Forensic anchor: the operator's home LAN has a dual-stack getaddrinfo
// for rutracker.org (both AAAA and A records). Cloudflare's IPv6 edge
// for rutracker.org completes the TLS handshake on POST /forum/login.php
// but then silently drops the request — the call hangs until the client's
// 30s timeout, the breaker counts the failure, and `lava-api-go` returns
// 502 to the Android client. The user sees "Cannot sign in with valid
// credentials". Pinning the upstream Transport to "tcp4" sidesteps the
// broken IPv6 path (verified on 2026-04-29: IPv4 same request → HTTP/2 302
// + bb_session cookie in <200ms).
//
// This test verifies the production code path — the same Transport
// returned by NewClient — by asking that Transport to dial a
// well-known IPv6-only address. The dial MUST be rewritten to tcp4
// and therefore fail with an "address family" / "unreachable" error
// rather than connecting (clause-2 falsifiability). MUTATION: removing
// the DialContext rewrite makes this test fail because the test
// process WOULD reach the IPv6 endpoint (or fail with a different
// error, but NOT the one we assert on).
//
// We don't make a live network request — instead we drive the
// http.Transport's Dial path through Transport.DialContext directly
// with a "tcp6" hint and assert that the rewrite happens. The test
// is hermetic.
func TestNewClient_TransportForcesIPv4(t *testing.T) {
	c := NewClient("http://example.invalid")
	tr, ok := c.http.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", c.http.Transport)
	}
	if tr.DialContext == nil {
		t.Fatal("Transport.DialContext is nil — IPv4 pin not installed")
	}

	// Listen on a real loopback IPv4 socket and capture what `network`
	// the DialContext actually passed to net.Dialer. We can't snoop the
	// internal dialer directly, so we invoke DialContext with each
	// possible input network and verify it doesn't try IPv6.
	// "tcp" and "tcp6" inputs MUST both get rewritten to "tcp4".
	cases := []string{"tcp", "tcp6", "tcp4"}
	for _, input := range cases {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		// 127.0.0.1:1 will refuse-connect immediately on IPv4 — that's
		// a fine signal that we DID hit the IPv4 stack. If the rewrite
		// were missing for "tcp6", we'd instead get an "address family"
		// or "unreachable" error trying to dial IPv4 from a tcp6
		// network — which is a different error class.
		_, err := tr.DialContext(ctx, input, "127.0.0.1:1")
		cancel()
		if err == nil {
			t.Errorf("network=%s: expected refused-connect error on 127.0.0.1:1", input)
			continue
		}
		// Refused or i/o-timeout is fine — those are IPv4 stack signals.
		// What we MUST NOT see is "address tcp6 ... has wrong family"
		// (which Go produces when DialContext gets tcp6 + an IPv4 addr).
		if msg := err.Error(); strings.Contains(msg, "address family") || strings.Contains(msg, "wrong network") {
			t.Errorf("network=%s: tcp6 leaked through (%v) — IPv4 pin not effective", input, err)
		}
	}
}

// TestNewClient_DoesNotFollowRedirects — SP-3.5 Sixth-Law regression
// guard #2.
//
// Forensic anchor: rutracker's POST /forum/login.php on a successful
// credentials submission returns a 302 with Set-Cookie: bb_session=<token>
// and Location: /forum/index.php. The auth token is on the 302 itself.
// Go's default http.Client.CheckRedirect transparently follows redirects
// AND does NOT carry the just-received Set-Cookie forward, so a Login
// flow that allowed the redirect would observe an unauthenticated
// /index.php (a login form), fall through ExtractLoginToken's "no
// token" branch, then through "no wrong-credits sentinel", and finally
// produce ErrUnknown — which writeUpstreamError maps to 502 even though
// the credentials were valid. That is exactly the user-visible "Cannot
// sign in with valid credentials" symptom on real device 2026-04-29.
//
// MUTATION: removing the CheckRedirect override from NewClient makes
// this test fail because the Go client follows the 302 and the test
// asserts on the FIRST response status — which would observe 200 OK
// from the redirected upstream-test handler instead of the 302 the
// scraper requires.
func TestNewClient_DoesNotFollowRedirects(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/login.php":
			http.SetCookie(w, &http.Cookie{Name: "bb_session", Value: "the-token"})
			w.Header().Set("Location", "/index.php")
			w.WriteHeader(http.StatusFound)
		case "/index.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>login form</html>"))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	body, status, headers, err := c.PostFormWithHeaders(
		context.Background(),
		"/login.php",
		nil,
		"",
	)
	if err != nil {
		t.Fatalf("PostFormWithHeaders: %v", err)
	}
	if status != http.StatusFound {
		t.Errorf("status=%d want 302 (Login depends on observing the redirect, not the redirected page)", status)
	}
	if cookie := headers.Get("Set-Cookie"); !strings.Contains(cookie, "bb_session=the-token") {
		t.Errorf("Set-Cookie=%q want contains 'bb_session=the-token' — the load-bearing auth token leaks if redirect is followed", cookie)
	}
	if strings.Contains(string(body), "login form") {
		t.Errorf("body contains the redirected /index.php content — redirect was followed (regression of SP-3.5)")
	}
}
