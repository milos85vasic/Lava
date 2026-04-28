package rutracker

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
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
