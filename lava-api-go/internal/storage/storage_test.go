package storage

import (
	"bytes"
	"context"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
)

// RunStorageConformance is the shared behavioral parity harness every
// Storage implementation MUST pass. It asserts on REAL observable state
// (the bytes returned by Get, the hit/miss outcome) — never on call counts.
// Both the Postgres impl (postgres_test.go, real podman Postgres) and the
// SQLite impl (sqlite_test.go, real temp-file DB) run this identical harness;
// any behavioral divergence between backends fails here.
//
// newStore must return a FRESH, empty store on each call. The caller is
// responsible for any teardown of the stores it constructs.
func RunStorageConformance(t *testing.T, newStore func() Storage) {
	t.Helper()
	ctx := context.Background()

	t.Run("set then get returns the stored bytes as a hit", func(t *testing.T) {
		s := newStore()
		key := "conformance/round-trip"
		want := []byte("the-real-payload-bytes")
		if err := s.Set(ctx, key, want, time.Minute); err != nil {
			t.Fatalf("Set: %v", err)
		}
		got, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get: %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("outcome=%q want %q", outcome, cache.OutcomeHit)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("value=%q want %q", got, want)
		}
	})

	t.Run("missing key returns a miss with nil value", func(t *testing.T) {
		s := newStore()
		got, outcome, err := s.Get(ctx, "conformance/never-stored")
		if err != nil {
			t.Fatalf("Get: %v", err)
		}
		if outcome != cache.OutcomeMiss {
			t.Errorf("outcome=%q want %q", outcome, cache.OutcomeMiss)
		}
		if got != nil {
			t.Errorf("value=%q want nil on miss", got)
		}
	})

	t.Run("expired entry returns a miss (lazy TTL expiry on read)", func(t *testing.T) {
		s := newStore()
		key := "conformance/ttl-expiry"
		// 1ns TTL → expires_at is in the past by the time we read.
		if err := s.Set(ctx, key, []byte("should-expire"), time.Nanosecond); err != nil {
			t.Fatalf("Set: %v", err)
		}
		// Ensure wall-clock has advanced past the 1ns expiry.
		time.Sleep(5 * time.Millisecond)
		got, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get: %v", err)
		}
		if outcome != cache.OutcomeMiss {
			t.Errorf("outcome=%q want %q (entry should have expired)", outcome, cache.OutcomeMiss)
		}
		if got != nil {
			t.Errorf("value=%q want nil for expired entry", got)
		}
	})

	t.Run("invalidate removes a live entry", func(t *testing.T) {
		s := newStore()
		key := "conformance/invalidate"
		if err := s.Set(ctx, key, []byte("v"), time.Minute); err != nil {
			t.Fatalf("Set: %v", err)
		}
		if err := s.Invalidate(ctx, key); err != nil {
			t.Fatalf("Invalidate: %v", err)
		}
		_, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get after Invalidate: %v", err)
		}
		if outcome != cache.OutcomeMiss {
			t.Errorf("outcome after Invalidate=%q want %q", outcome, cache.OutcomeMiss)
		}
	})

	t.Run("set overwrites an existing key", func(t *testing.T) {
		s := newStore()
		key := "conformance/overwrite"
		if err := s.Set(ctx, key, []byte("first"), time.Minute); err != nil {
			t.Fatalf("Set first: %v", err)
		}
		if err := s.Set(ctx, key, []byte("second"), time.Minute); err != nil {
			t.Fatalf("Set second: %v", err)
		}
		got, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get: %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("outcome=%q want %q", outcome, cache.OutcomeHit)
		}
		if string(got) != "second" {
			t.Errorf("value=%q want \"second\" (overwrite)", got)
		}
	})
}
