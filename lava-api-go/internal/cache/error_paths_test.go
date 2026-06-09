package cache_test

import (
	"context"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
)

// TestGetUnderlyingErrorBypasses pins the load-bearing fallthrough contract: when
// the real Postgres query path errors, Get MUST return (nil, OutcomeBypass, err)
// so the HTTP handler can fall through to the upstream tracker rather than serve a
// spurious miss/hit or 500. A cache that turns a DB outage into a wrong answer is
// the exact §6.J failure this test exists to prevent.
//
// The error is forced REALLY — by closing the live pgcache client so the next
// query hits a closed pool — not by mocking. The primary assertion is on the
// user-visible outcome (Bypass + non-nil error), per Sixth Law clause 3.
func TestGetUnderlyingErrorBypasses(t *testing.T) {
	c, closeInner, cleanup := mustClientWithCloser(t)
	defer cleanup()

	// Close the underlying pool: subsequent queries against a closed pool error
	// at the real driver layer — the same surface a production DB outage hits.
	closeInner()

	got, outcome, err := c.Get(context.Background(), "any-key")
	if err == nil {
		t.Fatal("Get against a closed Postgres pool returned nil error; expected the real driver error to surface")
	}
	if outcome != cache.OutcomeBypass {
		t.Errorf("outcome=%q want %q (a cache error MUST bypass to upstream, never serve a miss/hit)", outcome, cache.OutcomeBypass)
	}
	if got != nil {
		t.Errorf("value=%q want nil on the bypass path", string(got))
	}
}

// TestSetNilNormalizesToEmptyHit pins the BYTEA-NOT-NULL normalization at the
// cache.Client.Set boundary against REAL Postgres: Set(key, nil) MUST store an
// empty (non-nil) value that Get returns as a non-nil empty HIT — identical to
// Set(key, []byte{}). Without the nil→[]byte{} normalization, pgx binds Go nil as
// SQL NULL and the NOT NULL constraint rejects the insert (Set would error), so
// this test fails loudly if the normalization branch regresses.
func TestSetNilNormalizesToEmptyHit(t *testing.T) {
	c, cleanup := mustClient(t)
	defer cleanup()
	ctx := context.Background()

	if err := c.Set(ctx, "nil-key", nil, time.Minute); err != nil {
		t.Fatalf("Set(nil): %v (nil must be normalized to empty BYTEA, not bound as SQL NULL)", err)
	}
	got, outcome, err := c.Get(ctx, "nil-key")
	if err != nil {
		t.Fatalf("Get after Set(nil): %v", err)
	}
	if outcome != cache.OutcomeHit {
		t.Errorf("outcome=%q want %q (a normalized empty value is a HIT, not a miss)", outcome, cache.OutcomeHit)
	}
	if got == nil {
		t.Error("value is nil; Set(nil) must store a non-nil empty slice so Get returns a non-nil empty HIT")
	}
	if len(got) != 0 {
		t.Errorf("value=%q (len=%d) want empty", string(got), len(got))
	}
}
