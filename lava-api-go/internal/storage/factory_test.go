package storage

import (
	"context"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/config"
)

// TestNewSQLiteBackendRoundTrips drives the factory's sqlite branch end-to-end:
// New(cfg) MUST return a live, query-answering Storage whose readiness probe
// succeeds, and a value Set through that Storage MUST be retrievable as a HIT.
// The primary assertion is on REAL observable state (the persisted bytes), not
// on "New returned non-nil". This is the §6.J-compliant exercise of the path
// the composition root takes when LAVA_API_STORAGE_BACKEND=sqlite.
func TestNewSQLiteBackendRoundTrips(t *testing.T) {
	cfg := &config.Config{
		StorageBackend: config.BackendSQLite,
		SQLitePath:     filepath.Join(t.TempDir(), "factory.db"),
	}
	s, ready, err := New(cfg)
	if err != nil {
		t.Fatalf("New(sqlite): %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })

	// The readiness probe MUST report a live backend before any write: a probe
	// of the sentinel key returns nil error (handle answers queries).
	if err := ready(context.Background()); err != nil {
		t.Fatalf("readiness probe on fresh sqlite backend: %v", err)
	}

	ctx := context.Background()
	const key = "factory/roundtrip"
	want := []byte("persisted-through-the-factory")
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
	if string(got) != string(want) {
		t.Errorf("value=%q want %q (factory-built backend must persist the exact bytes)", got, want)
	}
}

// TestNewSQLiteReadinessProbeStaysLive confirms the readiness probe returned by
// the factory keeps reporting healthy across repeated calls — it is a real
// round-trip Get against the live handle, so it stays nil until Close. After
// Close the handle is gone and the probe MUST surface a non-nil error wrapped
// with the "sqlite:" prefix (the composition root relies on this to fail /ready).
func TestNewSQLiteReadinessProbeStaysLive(t *testing.T) {
	cfg := &config.Config{
		StorageBackend: config.BackendSQLite,
		SQLitePath:     filepath.Join(t.TempDir(), "ready.db"),
	}
	s, ready, err := New(cfg)
	if err != nil {
		t.Fatalf("New(sqlite): %v", err)
	}

	for i := 0; i < 3; i++ {
		if err := ready(context.Background()); err != nil {
			t.Fatalf("readiness probe call %d on live backend: %v", i, err)
		}
	}

	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	probeErr := ready(context.Background())
	if probeErr == nil {
		t.Fatal("readiness probe returned nil after Close; expected a wrapped DB error")
	}
	if !strings.Contains(probeErr.Error(), "sqlite:") {
		t.Errorf("readiness error %q does not carry the %q prefix the factory adds", probeErr, "sqlite:")
	}
}

// TestNewSQLiteMissingPathErrors exercises the factory's sqlite branch when the
// configured SQLite path is empty: NewSQLite refuses with a clear error, and the
// factory propagates it (no partially-constructed Storage, no nil-deref). The
// assertion is on the user-visible failure message a misconfigured operator sees.
func TestNewSQLiteMissingPathErrors(t *testing.T) {
	cfg := &config.Config{
		StorageBackend: config.BackendSQLite,
		SQLitePath:     "",
	}
	s, ready, err := New(cfg)
	if err == nil {
		t.Fatal("New(sqlite, empty path) returned nil error; expected refusal")
	}
	if s != nil || ready != nil {
		t.Errorf("New error path returned non-nil Storage/ready (%v/%v); both must be nil", s, ready)
	}
	if !strings.Contains(err.Error(), "sqlite path is empty") {
		t.Errorf("error %q does not name the empty-path cause", err)
	}
}

// TestNewUnknownBackendErrors exercises the factory's default branch: an
// unrecognised StorageBackend MUST refuse with a message naming the bad value,
// rather than silently defaulting to a backend. This is the user-visible
// failure an operator sees on a typo'd LAVA_API_STORAGE_BACKEND.
func TestNewUnknownBackendErrors(t *testing.T) {
	cfg := &config.Config{StorageBackend: "redis-typo"}
	s, ready, err := New(cfg)
	if err == nil {
		t.Fatal("New(unknown backend) returned nil error; expected refusal")
	}
	if s != nil || ready != nil {
		t.Errorf("New error path returned non-nil Storage/ready; both must be nil")
	}
	if !strings.Contains(err.Error(), "redis-typo") {
		t.Errorf("error %q does not echo the unknown backend value", err)
	}
}
