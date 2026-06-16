package storage

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
)

// §11.4.85 Chaos Test Mandate — storage layer (SQLite-backed Storage).
//
// Chaos tests inject faults and assert the implementation degrades GRACEFULLY
// (no panic, an error surfaced through the documented contract) rather than
// crashing the process. Per the Storage contract, a real DB error surfaces as
// (nil, OutcomeBypass, err) from Get and a non-nil error from Set/Invalidate —
// the handler layer then falls through to upstream. The load-bearing assertion
// is "the process survives + the contract is honored", which is exactly what a
// real user relies on when the disk/handle misbehaves.
//
// recoverGuard fails the test if the wrapped call panics — that IS the chaos
// assertion (graceful, no panic).

func recoverGuard(t *testing.T, name string, fn func()) {
	t.Helper()
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("%s PANICKED (must degrade gracefully, not panic): %v", name, r)
		}
	}()
	fn()
}

// TestChaosNilAndEmptyInputs feeds empty keys and nil values. The chaos
// property under test is GRACEFUL degradation: no panic, and any failure is
// surfaced through the documented contract (a non-nil error from Set).
//
// RESOLVED PARITY GAP (2026-06-08): the SQLite backend formerly handled a NIL
// value and an EMPTY-BUT-NON-NIL value INCONSISTENTLY —
//   - Set(key, []byte{}, ttl)  → succeeded; Get read back a non-nil empty HIT.
//   - Set(key, nil, ttl)        → ERRORED with
//     "constraint failed: NOT NULL constraint failed: response_cache.value (1299)"
//     because a nil Go []byte binds to SQL NULL against the NOT NULL column.
//
// This chaos test flagged it as a candidate cross-backend parity gap and
// predicted that resolving it would require updating this subtest. It was
// resolved: sqliteStorage.Set (and internal/cache.Client.Set for Postgres) now
// normalize nil → []byte{} at the Set boundary, so Set(key, nil) and
// Set(key, []byte{}) are observably identical on BOTH backends — both store an
// empty blob that Get returns as a non-nil empty HIT. The pinned cross-backend
// contract + its falsifiability rehearsal live in nil_empty_parity_test.go.
// This subtest now asserts that NEW, correct round-trip behavior.
//
// FALSIFIABILITY: if Set(nil) ever panicked or nil-deref'd, recoverGuard fires.
// Removing the nil→empty guard from sqliteStorage.Set re-introduces the NOT
// NULL error, failing the "Set(nil) must succeed" assertion below.
func TestChaosNilAndEmptyInputs(t *testing.T) {
	ctx := context.Background()
	s := newStressStore(t)

	t.Run("nil value round-trips as a non-nil empty HIT (parity with empty slice)", func(t *testing.T) {
		var setErr error
		recoverGuard(t, "Set(nil value)", func() {
			setErr = s.Set(ctx, "chaos/nil-value", nil, time.Hour)
		})
		// Set(nil) must succeed (nil is normalized to an empty blob), never panic,
		// never surface a NOT NULL error. (See RESOLVED PARITY GAP above.)
		if setErr != nil {
			t.Fatalf("Set(nil value) returned error %v; want success (nil must normalize to an empty blob for cross-backend parity)", setErr)
		}
		// A stored nil reads back as a HIT serving a non-nil empty body — NOT a
		// miss. A miss here would silently re-hit upstream on every request for
		// this (empty-bodied) cache entry.
		got, outcome, err := s.Get(ctx, "chaos/nil-value")
		if err != nil {
			t.Fatalf("Get(nil value key): %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("nil-value outcome=%q want hit (stored nil must round-trip, not fall through to upstream)", outcome)
		}
		if got == nil {
			t.Errorf("nil-value Get returned a nil slice; want non-nil empty (a HIT must not look like a MISS)")
		}
		if len(got) != 0 {
			t.Errorf("nil-value Get returned %d bytes; want 0 (empty)", len(got))
		}
	})

	t.Run("empty key is a valid distinct key", func(t *testing.T) {
		recoverGuard(t, "Set(empty key)", func() {
			if err := s.Set(ctx, "", []byte("value-under-empty-key"), time.Hour); err != nil {
				t.Fatalf("Set(empty key): %v", err)
			}
		})
		got, outcome, err := s.Get(ctx, "")
		if err != nil {
			t.Fatalf("Get(empty key): %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("empty-key outcome=%q want hit", outcome)
		}
		if string(got) != "value-under-empty-key" {
			t.Errorf("empty-key value=%q want %q", got, "value-under-empty-key")
		}
	})

	t.Run("get/invalidate of empty/never-set keys are graceful misses", func(t *testing.T) {
		recoverGuard(t, "Get/Invalidate(unset)", func() {
			_, outcome, err := s.Get(ctx, "chaos/never-set")
			if err != nil || outcome != cache.OutcomeMiss {
				t.Errorf("Get(unset)=(%q,%v) want (miss,nil)", outcome, err)
			}
			if err := s.Invalidate(ctx, "chaos/never-set"); err != nil {
				t.Errorf("Invalidate(unset): %v (deleting a non-existent key must be a no-op success)", err)
			}
		})
	})
}

// TestChaosClosedDBHandle closes the backend, then exercises every operation.
// Each MUST return an error (Get → OutcomeBypass + err) WITHOUT panicking. This
// is the real fault a handler sees if Close races with an in-flight request, or
// if a operator triggers shutdown while traffic is live.
//
// FALSIFIABILITY: if any op panicked on a closed handle, recoverGuard fails. If
// Get returned (value, Hit) against a closed DB, the error assertion fails.
func TestChaosClosedDBHandle(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "closed.db")
	s, err := newSQLiteStorage(path)
	if err != nil {
		t.Fatalf("newSQLiteStorage: %v", err)
	}
	// Seed a row so a hit would be possible IF the handle were live.
	if err := s.Set(ctx, "chaos/seed", []byte("seed"), time.Hour); err != nil {
		t.Fatalf("seed Set: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	recoverGuard(t, "ops on closed handle", func() {
		_, outcome, err := s.Get(ctx, "chaos/seed")
		if err == nil {
			t.Errorf("Get on closed DB returned nil error; want a surfaced DB error")
		}
		if outcome != cache.OutcomeBypass {
			t.Errorf("Get on closed DB outcome=%q want %q (so the handler falls through to upstream)", outcome, cache.OutcomeBypass)
		}
		if err := s.Set(ctx, "chaos/seed", []byte("x"), time.Hour); err == nil {
			t.Errorf("Set on closed DB returned nil error; want a surfaced DB error")
		}
		if err := s.Invalidate(ctx, "chaos/seed"); err == nil {
			t.Errorf("Invalidate on closed DB returned nil error; want a surfaced DB error")
		}
	})

	// Close remains idempotent even after the faulted ops.
	if err := s.Close(); err != nil {
		t.Errorf("second Close after faulted ops: %v", err)
	}
}

// TestChaosContextCancellationMidOp cancels the context BEFORE the op runs and
// confirms the op fails gracefully (no panic, error surfaced). An already-cancelled
// context is the deterministic, reproducible form of "cancelled mid-op": the
// database/sql layer observes ctx.Err() and aborts the query. A non-cancelled
// control op on the same store MUST still succeed, proving the cancellation did
// not corrupt the handle.
//
// FALSIFIABILITY: if the op ignored the cancelled context AND returned a hit,
// the error/outcome assertion fails. If cancellation left the handle unusable,
// the control op fails.
func TestChaosContextCancellationMidOp(t *testing.T) {
	bg := context.Background()
	s := newStressStore(t)
	if err := s.Set(bg, "chaos/ctx", []byte("live"), time.Hour); err != nil {
		t.Fatalf("seed Set: %v", err)
	}

	cancelled, cancel := context.WithCancel(bg)
	cancel() // already cancelled

	recoverGuard(t, "ops with cancelled ctx", func() {
		// Get with a cancelled ctx: must not panic. It either surfaces the
		// context error as (nil, Bypass, err) or — if the driver completes the
		// trivial read before noticing — returns a normal result. Both are
		// graceful; a panic is not.
		_, outcome, err := s.Get(cancelled, "chaos/ctx")
		if err != nil && outcome != cache.OutcomeBypass {
			t.Errorf("Get(cancelled) returned err=%v but outcome=%q; a real DB/ctx error must be Bypass", err, outcome)
		}
		// Set/Invalidate with cancelled ctx: must not panic. Error is acceptable
		// and expected; nil is acceptable if the driver completed first.
		_ = s.Set(cancelled, "chaos/ctx", []byte("x"), time.Hour)
		_ = s.Invalidate(cancelled, "chaos/ctx")
	})

	// Control: the store is still usable with a live context (cancellation did
	// not wedge the single connection).
	if _, _, err := s.Get(bg, "chaos/ctx"); err != nil {
		t.Errorf("control Get after cancelled ops failed: %v (cancellation wedged the handle)", err)
	}
	if err := s.Set(bg, "chaos/ctx2", []byte("after-cancel"), time.Hour); err != nil {
		t.Errorf("control Set after cancelled ops failed: %v", err)
	}
	got, outcome, err := s.Get(bg, "chaos/ctx2")
	if err != nil || outcome != cache.OutcomeHit || string(got) != "after-cancel" {
		t.Errorf("control read-back=(%q,%q,%v) want (\"after-cancel\",hit,nil)", got, outcome, err)
	}
}

// TestChaosCorruptDatabaseFile points the backend at a file whose contents are
// NOT a valid SQLite database. Opening / migrating MUST fail gracefully with an
// error (no panic, no partially-constructed Storage). This is the fault an
// operator hits when a volume is restored from a truncated backup or a
// half-written file.
//
// FALSIFIABILITY: if newSQLiteStorage panicked on a corrupt file, recoverGuard
// fails. If it returned a non-nil Storage alongside an error, the nil-Storage
// assertion fails.
func TestChaosCorruptDatabaseFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "corrupt.db")
	// Write garbage that is not a SQLite header ("SQLite format 3\000").
	if err := os.WriteFile(path, []byte("this is definitely not a sqlite database file, just junk bytes\x00\x01\x02"), 0o600); err != nil {
		t.Fatalf("write corrupt file: %v", err)
	}

	var s *sqliteStorage
	var openErr error
	recoverGuard(t, "open corrupt DB", func() {
		s, openErr = newSQLiteStorage(path)
	})
	if openErr == nil {
		if s != nil {
			_ = s.Close()
		}
		t.Fatal("newSQLiteStorage on a corrupt file returned nil error; expected a surfaced corruption/migration error")
	}
	if s != nil {
		_ = s.Close()
		t.Errorf("newSQLiteStorage returned a non-nil Storage alongside an error; the error path must yield nil Storage")
	}
}

// TestChaosLockedDatabaseGraceful exercises behavior when two independent
// backends open the SAME on-disk file. With WAL + busy_timeout(5000) from the
// DSN, concurrent readers/writers coordinate; a brief contention must NOT
// produce a panic and writes from BOTH handles must be observable (last-writer
// semantics per key). This stands in for the "locked DB" fault: SQLite's lock
// arbitration, not a crash.
//
// FALSIFIABILITY: if opening a second handle on the same file panicked, or a
// write from one handle was invisible to the other after commit, the assertions
// fail. (Note: each handle uses MaxOpenConns(1); cross-handle visibility relies
// on WAL + a fresh read transaction per Get.)
func TestChaosLockedDatabaseGraceful(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	path := filepath.Join(dir, "shared.db")

	var s1, s2 *sqliteStorage
	var err1, err2 error
	recoverGuard(t, "open two handles on same file", func() {
		s1, err1 = newSQLiteStorage(path)
		if err1 == nil {
			s2, err2 = newSQLiteStorage(path)
		}
	})
	if err1 != nil {
		t.Fatalf("open first handle: %v", err1)
	}
	t.Cleanup(func() { _ = s1.Close() })
	if err2 != nil {
		// A second open failing is itself graceful (an error, not a panic). Record
		// and stop — the no-panic property already held.
		t.Logf("second handle open returned err=%v (graceful — no panic); skipping cross-handle visibility", err2)
		return
	}
	t.Cleanup(func() { _ = s2.Close() })

	recoverGuard(t, "interleaved writes across two handles", func() {
		if err := s1.Set(ctx, "chaos/locked/a", []byte("from-s1"), time.Hour); err != nil {
			t.Fatalf("s1.Set: %v", err)
		}
		if err := s2.Set(ctx, "chaos/locked/b", []byte("from-s2"), time.Hour); err != nil {
			t.Fatalf("s2.Set: %v", err)
		}
	})

	// Cross-handle visibility after commit (WAL): s2 sees s1's write and vice versa.
	if got, outcome, err := s2.Get(ctx, "chaos/locked/a"); err != nil || outcome != cache.OutcomeHit || string(got) != "from-s1" {
		t.Errorf("s2 read of s1's write=(%q,%q,%v) want (\"from-s1\",hit,nil)", got, outcome, err)
	}
	if got, outcome, err := s1.Get(ctx, "chaos/locked/b"); err != nil || outcome != cache.OutcomeHit || string(got) != "from-s2" {
		t.Errorf("s1 read of s2's write=(%q,%q,%v) want (\"from-s2\",hit,nil)", got, outcome, err)
	}
}

// TestChaosDiskFullSimulation attempts to simulate a disk-full condition. A
// portable, hermetic disk-full simulation is not feasible in a unit test without
// root/cgroup/loopback-mount privileges (forbidden per §6.U no-sudo) or a custom
// VFS shim modernc.org/sqlite does not expose. Rather than ship a bluff that
// pretends to test disk-full, this test SKIPs with an explicit reason and points
// at where a real disk-full assertion belongs (the §11.4.85 chaos harness running
// inside a Containers-submodule VM with a size-capped tmpfs volume).
//
// FALSIFIABILITY: n/a — a SKIP asserts nothing by design; the honest SKIP-with-reason
// is the §11.4.3 / §6.J-compliant alternative to a faked disk-full PASS.
func TestChaosDiskFullSimulation(t *testing.T) {
	t.Skip("disk-full simulation requires a size-capped volume (loopback mount / cgroup / tmpfs quota) " +
		"unavailable in a hermetic unit test without privileges forbidden by §6.U; " +
		"belongs in the §11.4.85 chaos harness inside a Containers-submodule VM with a capped tmpfs. " +
		"SKIP-with-reason per §11.4.3 rather than a faked disk-full PASS.")
}
