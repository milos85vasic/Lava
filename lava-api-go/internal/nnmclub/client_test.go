package nnmclub

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"digital.vasic.recovery/pkg/breaker"
)

// TestRetriesOnTransient5xx verifies the user-visible recovery behaviour added
// to defend the circuit breaker: the upstream returns a transient 503 on the
// FIRST attempt then a healthy 200 on the second, and the caller gets the
// successful body — NOT an error — AND the breaker is NOT tripped toward OPEN.
//
// This is the load-bearing anti-bluff assertion. Before this change a single
// transient timeout/5xx counted as a breaker failure; ~5 of them OPEN the
// breaker for ~10s and lock the user out. The in-closure retry converts the
// transient into a success, so breaker.GetFailures() stays 0 and the state
// stays Closed (a successful Execute resets the consecutive-failure counter).
//
// FALSIFIABILITY REHEARSAL: in client.go Fetch, change the http.Do error and
// the 5xx branches to return `false` (not transient) instead of `true` — the
// retry is then bypassed. This test MUST fail: the first 503 is surfaced as a
// terminal error, Fetch returns non-nil err, and the assertions on a recovered
// 200 body + breaker-not-tripped fire.
func TestRetriesOnTransient5xx(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&calls, 1) == 1 {
			w.WriteHeader(http.StatusServiceUnavailable) // transient 503 on first attempt
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("recovered"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	body, status, err := c.Fetch(context.Background(), "/", "")
	if err != nil {
		t.Fatalf("expected transient 503 to be retried into success, got err: %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("status=%d want 200 (recovered after retry)", status)
	}
	if string(body) != "recovered" {
		t.Fatalf("body=%q want %q (the post-retry 200 body)", string(body), "recovered")
	}
	if got := atomic.LoadInt32(&calls); got != 2 {
		t.Fatalf("upstream hit %d times, want 2 (one 503 + one retried 200)", got)
	}
	// The breaker MUST NOT have moved toward OPEN: a recovered transient is a
	// success as far as the breaker is concerned.
	if f := c.breaker.GetFailures(); f != 0 {
		t.Fatalf("breaker failure count=%d want 0 — a single slow upstream tripped the breaker", f)
	}
	if st := c.breaker.GetState(); st != breaker.StateClosed {
		t.Fatalf("breaker state=%v want Closed — transient retry must not OPEN the breaker", st)
	}
}

// TestTerminalErrorNotRetried verifies a terminal upstream response (404) is
// surfaced immediately and is NOT retried — retrying a terminal error only
// wastes the ctx-bounded budget and delays the user-visible failure.
//
// FALSIFIABILITY REHEARSAL: in client.go Fetch, change the success/`< 500`
// return from `false` to `true` (classify everything as transient) — this test
// MUST fail because the 404 would then be retried maxAttempts (3) times instead
// of once.
func TestTerminalErrorNotRetried(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusNotFound) // terminal — must not be retried
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, status, err := c.Fetch(context.Background(), "/", "")
	if err != nil {
		t.Fatalf("404 should surface as status, not error: %v", err)
	}
	if status != http.StatusNotFound {
		t.Fatalf("status=%d want 404", status)
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("upstream hit %d times, want 1 — terminal 404 was retried", got)
	}
}
