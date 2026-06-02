package storage

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
)

// openSQLiteForTest constructs a *sqliteStorage on a fresh on-disk temp DB and
// returns the concrete type so tests can reach internal methods (sweepExpired,
// the *sql.DB handle) that the Storage interface does not expose. On-disk (not
// :memory:) so the WAL + auto_vacuum + reclamation assertions are real.
func openSQLiteForTest(t *testing.T) *sqliteStorage {
	t.Helper()
	path := filepath.Join(t.TempDir(), "gc.db")
	s, err := newSQLiteStorage(path)
	if err != nil {
		t.Fatalf("newSQLiteStorage: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

// countRows returns the PHYSICAL row count in response_cache via a direct
// COUNT(*) — bypassing the lazy-expiry WHERE clause that Get applies. This is
// the load-bearing assertion for the GC sweep: an expired row that is merely
// unreadable (filtered on read) is still physically present and still growing
// the file; only a sweep that DELETEs it drops the COUNT.
func countRows(t *testing.T, s *sqliteStorage) int {
	t.Helper()
	var n int
	if err := s.db.QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM response_cache`).Scan(&n); err != nil {
		t.Fatalf("COUNT(*): %v", err)
	}
	return n
}

// TestSQLiteSweepPhysicallyDeletesExpiredRows is the Finding-1 regression test.
//
// It inserts a key with a tiny TTL, waits until expired, triggers the sweep
// DIRECTLY (sweepExpired — NOT the 10-minute background timer), and asserts via
// a direct SELECT COUNT(*) that the expired row is PHYSICALLY GONE, while a
// live (long-TTL) row and a never-expiring row both survive.
//
// FALSIFIABILITY (Sixth Law clause 2 / §6.J): if sweepExpired is made a no-op
// (return 0, nil without the DELETE), the post-sweep COUNT stays at 3 and this
// test fails with "after sweep COUNT=3 want 1 ...". Rehearsal recorded in the
// commit body.
func TestSQLiteSweepPhysicallyDeletesExpiredRows(t *testing.T) {
	ctx := context.Background()
	s := openSQLiteForTest(t)

	// One expired entry (1ns TTL → already in the past after the sleep).
	if err := s.Set(ctx, "gc/expired", []byte("stale"), time.Nanosecond); err != nil {
		t.Fatalf("Set expired: %v", err)
	}
	// One live entry (long TTL).
	if err := s.Set(ctx, "gc/live", []byte("fresh"), time.Hour); err != nil {
		t.Fatalf("Set live: %v", err)
	}
	// One never-expiring entry (ttl<=0 → expires_at NULL; the sweep MUST NOT
	// touch NULL-expiry rows).
	if err := s.Set(ctx, "gc/forever", []byte("permanent"), 0); err != nil {
		t.Fatalf("Set forever: %v", err)
	}

	// Wall-clock past the 1ns expiry.
	time.Sleep(5 * time.Millisecond)

	// Pre-condition: all three rows are PHYSICALLY present (lazy expiry has
	// NOT removed the expired row — only the sweep can).
	if got := countRows(t, s); got != 3 {
		t.Fatalf("before sweep COUNT=%d want 3 (expired row should still be physically present pre-sweep)", got)
	}

	deleted, err := s.sweepExpired(ctx)
	if err != nil {
		t.Fatalf("sweepExpired: %v", err)
	}
	if deleted != 1 {
		t.Errorf("sweepExpired deleted=%d want 1", deleted)
	}

	// Load-bearing assertion: the expired row is PHYSICALLY deleted; the live
	// row and the never-expiring row remain.
	if got := countRows(t, s); got != 2 {
		t.Fatalf("after sweep COUNT=%d want 2 (only the expired row should be physically deleted)", got)
	}

	// User-visible behavior: live + forever still hit; expired is a miss.
	if _, outcome, _ := s.Get(ctx, "gc/live"); outcome != cache.OutcomeHit {
		t.Errorf("gc/live outcome=%q want hit after sweep", outcome)
	}
	if _, outcome, _ := s.Get(ctx, "gc/forever"); outcome != cache.OutcomeHit {
		t.Errorf("gc/forever outcome=%q want hit after sweep", outcome)
	}
	if _, outcome, _ := s.Get(ctx, "gc/expired"); outcome != cache.OutcomeMiss {
		t.Errorf("gc/expired outcome=%q want miss after sweep", outcome)
	}
}

// TestSQLiteWALEngagedOnDisk is the Finding-2 regression test: after
// construction on a real on-disk path, PRAGMA journal_mode MUST report "wal".
// A silently-weaker journal mode (e.g. "delete") loses the concurrent-reader
// guarantee the DSN pragma was meant to provide.
//
// FALSIFIABILITY: drop the journal_mode(WAL) pragma from the DSN → journal_mode
// reports "delete" and this test fails with `journal_mode="delete" want "wal"`.
func TestSQLiteWALEngagedOnDisk(t *testing.T) {
	s := openSQLiteForTest(t)
	var mode string
	if err := s.db.QueryRowContext(context.Background(),
		`PRAGMA journal_mode`).Scan(&mode); err != nil {
		t.Fatalf("PRAGMA journal_mode: %v", err)
	}
	if mode != "wal" {
		t.Errorf("journal_mode=%q want \"wal\" (WAL must engage for the on-disk embedded writer)", mode)
	}
}

// TestSQLiteAutoVacuumIncremental asserts that auto_vacuum is engaged in
// INCREMENTAL mode (value 2) so freed pages are tracked on the freelist and
// reused by subsequent writes — keeping the embedded file bounded rather than
// growing forever. Verified-correct engagement order (PRAGMA auto_vacuum +
// VACUUM before table creation, with MaxOpenConns(1)) lives in newSQLiteStorage.
//
// FALSIFIABILITY: remove the auto_vacuum engagement from newSQLiteStorage →
// PRAGMA auto_vacuum reports 0 and this test fails with `auto_vacuum=0 want 2`.
func TestSQLiteAutoVacuumIncremental(t *testing.T) {
	s := openSQLiteForTest(t)
	var av int
	if err := s.db.QueryRowContext(context.Background(),
		`PRAGMA auto_vacuum`).Scan(&av); err != nil {
		t.Fatalf("PRAGMA auto_vacuum: %v", err)
	}
	if av != 2 { // 0=NONE, 1=FULL, 2=INCREMENTAL
		t.Errorf("auto_vacuum=%d want 2 (INCREMENTAL)", av)
	}
}

// TestSQLiteCloseStopsGCGoroutine asserts Close() terminates the background GC
// goroutine without leaking. Close must be safe to call (it cancels the
// goroutine's context + waits) and idempotent.
//
// FALSIFIABILITY: if Close() does not cancel the goroutine context, the
// WaitGroup wait in Close blocks forever and this test hangs → caught by the
// test's own deadline.
func TestSQLiteCloseStopsGCGoroutine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "close.db")
	s, err := newSQLiteStorage(path)
	if err != nil {
		t.Fatalf("newSQLiteStorage: %v", err)
	}
	done := make(chan error, 1)
	go func() { done <- s.Close() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Close: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close() did not return within 5s — GC goroutine likely leaked")
	}
	// Idempotent second Close.
	if err := s.Close(); err != nil {
		t.Errorf("second Close: %v", err)
	}
}

// TestSQLiteZeroLengthValueRoundTrip is the MINOR conformance gap from the
// review: a zero-length []byte value MUST round-trip as a HIT (not be confused
// with a miss / NULL), matching Postgres BYTEA empty-value semantics.
//
// FALSIFIABILITY: if Get returned a miss for empty values (e.g. by treating
// len==0 as absent), this test fails with `outcome=miss want hit`.
func TestSQLiteZeroLengthValueRoundTrip(t *testing.T) {
	ctx := context.Background()
	s := openSQLiteForTest(t)
	key := "conformance/empty-blob"
	if err := s.Set(ctx, key, []byte{}, time.Minute); err != nil {
		t.Fatalf("Set empty: %v", err)
	}
	got, outcome, err := s.Get(ctx, key)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if outcome != cache.OutcomeHit {
		t.Errorf("outcome=%q want hit for a stored zero-length value", outcome)
	}
	if got == nil {
		t.Errorf("value=nil want non-nil empty slice for a stored zero-length value")
	}
	if len(got) != 0 {
		t.Errorf("len(value)=%d want 0", len(got))
	}
}

// ensure the *sql.DB import is used even if the rest of the file changes shape.
var _ = sql.ErrNoRows
