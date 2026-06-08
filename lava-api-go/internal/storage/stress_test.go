package storage

import (
	"bytes"
	"context"
	"fmt"
	"path/filepath"
	"sort"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
)

// §11.4.85 Stress Test Mandate — storage layer (SQLite-backed Storage).
//
// These tests exercise the REAL pure-Go SQLite Storage on a fresh on-disk
// temp DB (no external service), under sustained load, concurrent access, and
// boundary inputs. The primary assertions are on REAL observable state (the
// bytes returned by Get, the hit/miss outcome) per §6.J — never on call counts.
//
// Run with -race to make the concurrent test a real data-race detector:
//
//	go test ./internal/storage/... -race -count=1 -run 'Stress|Chaos'

// newStressStore constructs a *sqliteStorage on a fresh on-disk temp DB. On-disk
// (not :memory:) so WAL + the single-writer connection model are real.
func newStressStore(t *testing.T) *sqliteStorage {
	t.Helper()
	path := filepath.Join(t.TempDir(), "stress.db")
	s, err := newSQLiteStorage(path)
	if err != nil {
		t.Fatalf("newSQLiteStorage: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

// percentile returns the p-th percentile (0..100) of a sorted-ascending slice
// of durations using nearest-rank. samples MUST be non-empty.
func percentile(sortedAsc []time.Duration, p float64) time.Duration {
	if len(sortedAsc) == 0 {
		return 0
	}
	if p <= 0 {
		return sortedAsc[0]
	}
	if p >= 100 {
		return sortedAsc[len(sortedAsc)-1]
	}
	// nearest-rank: ceil(p/100 * N) → 1-based index
	rank := int(p/100*float64(len(sortedAsc)) + 0.999999)
	if rank < 1 {
		rank = 1
	}
	if rank > len(sortedAsc) {
		rank = len(sortedAsc)
	}
	return sortedAsc[rank-1]
}

// TestStressSustainedSetGetCycles drives ≥100 Set/Get cycles back-to-back and
// reports p50/p95/p99 latency for each operation class. The load-bearing
// assertion is correctness under sustained load: every Get MUST return the
// exact bytes the matching Set stored, as a HIT. Latency is reported (logged),
// and a generous ceiling guards against catastrophic regression (a single op
// taking longer than 2s on an embedded local SQLite is a real defect).
//
// FALSIFIABILITY: if Set silently dropped writes (e.g. ON CONFLICT removed) or
// Get returned stale bytes, the bytes.Equal assertion fails with a key-tagged
// message naming the cycle. Rehearsal recorded in the worklog evidence.
func TestStressSustainedSetGetCycles(t *testing.T) {
	ctx := context.Background()
	s := newStressStore(t)

	const cycles = 500 // ≥100 per mandate; 500 gives stabler percentiles
	setLat := make([]time.Duration, 0, cycles)
	getLat := make([]time.Duration, 0, cycles)

	for i := 0; i < cycles; i++ {
		key := fmt.Sprintf("stress/cycle/%06d", i)
		want := []byte(fmt.Sprintf("payload-for-cycle-%d-with-some-body-bytes", i))

		t0 := time.Now()
		if err := s.Set(ctx, key, want, time.Hour); err != nil {
			t.Fatalf("cycle %d Set: %v", i, err)
		}
		setLat = append(setLat, time.Since(t0))

		t1 := time.Now()
		got, outcome, err := s.Get(ctx, key)
		getLat = append(getLat, time.Since(t1))
		if err != nil {
			t.Fatalf("cycle %d Get: %v", i, err)
		}
		if outcome != cache.OutcomeHit {
			t.Fatalf("cycle %d outcome=%q want hit", i, outcome)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("cycle %d value=%q want %q (sustained-load correctness)", i, got, want)
		}
	}

	sort.Slice(setLat, func(a, b int) bool { return setLat[a] < setLat[b] })
	sort.Slice(getLat, func(a, b int) bool { return getLat[a] < getLat[b] })

	t.Logf("STRESS sustained cycles=%d", cycles)
	t.Logf("  SET latency  p50=%v p95=%v p99=%v max=%v",
		percentile(setLat, 50), percentile(setLat, 95), percentile(setLat, 99), setLat[len(setLat)-1])
	t.Logf("  GET latency  p50=%v p95=%v p99=%v max=%v",
		percentile(getLat, 50), percentile(getLat, 95), percentile(getLat, 99), getLat[len(getLat)-1])

	// Catastrophic-regression guard (NOT a micro-benchmark assertion): any single
	// op exceeding 2s on a local embedded SQLite indicates a real defect (lock
	// storm, runaway VACUUM). p99 under load must stay well below that.
	if p99 := percentile(setLat, 99); p99 > 2*time.Second {
		t.Errorf("SET p99=%v exceeds 2s ceiling — sustained-load latency regression", p99)
	}
	if p99 := percentile(getLat, 99); p99 > 2*time.Second {
		t.Errorf("GET p99=%v exceeds 2s ceiling — sustained-load latency regression", p99)
	}
}

// TestStressConcurrentAccessNoRace runs ≥10 goroutines hammering Set/Get/Invalidate
// concurrently. Under -race this is a real data-race detector for the Storage
// implementation (the single-connection model + database/sql goroutine safety).
// The load-bearing assertion: after the storm, a deterministic set of keys each
// goroutine wrote-and-did-not-invalidate MUST be readable as HITs with the exact
// final bytes — proving no lost writes / corruption under contention.
//
// FALSIFIABILITY: if SetMaxOpenConns(1) were removed AND modernc were not
// goroutine-safe, -race would flag a data race OR the final read-back would
// mismatch. The post-storm read-back is the user-visible correctness assertion.
func TestStressConcurrentAccessNoRace(t *testing.T) {
	ctx := context.Background()
	s := newStressStore(t)

	const goroutines = 16 // ≥10 per mandate
	const opsPerG = 200

	var wg sync.WaitGroup
	var setErrs, getErrs, invErrs int64

	// Each goroutine owns a disjoint key namespace so its final-state read-back
	// is deterministic regardless of interleaving with other goroutines.
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < opsPerG; i++ {
				key := fmt.Sprintf("stress/conc/g%02d/k%04d", g, i%50) // 50 keys reused → contention + overwrites
				val := []byte(fmt.Sprintf("g%d-i%d", g, i))
				if err := s.Set(ctx, key, val, time.Hour); err != nil {
					atomic.AddInt64(&setErrs, 1)
					continue
				}
				if _, _, err := s.Get(ctx, key); err != nil {
					atomic.AddInt64(&getErrs, 1)
				}
				// Occasionally invalidate a different key to mix delete traffic.
				if i%17 == 0 {
					if err := s.Invalidate(ctx, fmt.Sprintf("stress/conc/g%02d/k%04d", g, (i+1)%50)); err != nil {
						atomic.AddInt64(&invErrs, 1)
					}
				}
			}
			// Final deterministic write per goroutine, NOT invalidated, so the
			// read-back below is exact.
			finalKey := fmt.Sprintf("stress/conc/final/g%02d", g)
			finalVal := []byte(fmt.Sprintf("final-value-g%d", g))
			if err := s.Set(ctx, finalKey, finalVal, time.Hour); err != nil {
				atomic.AddInt64(&setErrs, 1)
			}
		}(g)
	}
	wg.Wait()

	if setErrs != 0 || getErrs != 0 || invErrs != 0 {
		t.Fatalf("concurrent op errors set=%d get=%d inv=%d (expected 0 — no SQLITE_BUSY/corruption under contention)",
			setErrs, getErrs, invErrs)
	}

	// Load-bearing: every goroutine's final write is readable as the exact bytes.
	for g := 0; g < goroutines; g++ {
		finalKey := fmt.Sprintf("stress/conc/final/g%02d", g)
		want := []byte(fmt.Sprintf("final-value-g%d", g))
		got, outcome, err := s.Get(ctx, finalKey)
		if err != nil {
			t.Fatalf("read-back g%d: %v", g, err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("read-back g%d outcome=%q want hit (lost write under contention?)", g, outcome)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("read-back g%d value=%q want %q (corruption/lost write under contention)", g, got, want)
		}
	}
	t.Logf("STRESS concurrent goroutines=%d opsPerG=%d total=%d — 0 errors, all final writes intact",
		goroutines, opsPerG, goroutines*opsPerG)
}

// TestStressBoundaryValues exercises boundary inputs that a naive impl would
// mishandle: an empty (zero-length) value, a large (1 MiB) value, and key
// collisions (repeated overwrite of one key from many distinct payloads). Each
// asserts on the exact bytes / outcome a real consumer would observe.
//
// FALSIFIABILITY: if the large value were truncated by a column-size limit, or
// the empty value were treated as a miss, or an overwrite kept the old bytes,
// the corresponding bytes.Equal / outcome assertion fails.
func TestStressBoundaryValues(t *testing.T) {
	ctx := context.Background()
	s := newStressStore(t)

	t.Run("empty value round-trips as a hit", func(t *testing.T) {
		key := "stress/boundary/empty"
		if err := s.Set(ctx, key, []byte{}, time.Hour); err != nil {
			t.Fatalf("Set empty: %v", err)
		}
		got, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get: %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("empty-value outcome=%q want hit", outcome)
		}
		if got == nil || len(got) != 0 {
			t.Errorf("empty-value got=%v (len %d) want non-nil zero-length slice", got, len(got))
		}
	})

	t.Run("large value round-trips intact", func(t *testing.T) {
		key := "stress/boundary/large"
		const size = 1 << 20 // 1 MiB
		want := make([]byte, size)
		for i := range want {
			want[i] = byte(i % 251) // non-trivial, non-constant pattern
		}
		if err := s.Set(ctx, key, want, time.Hour); err != nil {
			t.Fatalf("Set large: %v", err)
		}
		got, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get large: %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("large-value outcome=%q want hit", outcome)
		}
		if len(got) != size {
			t.Fatalf("large-value len=%d want %d (truncation?)", len(got), size)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("large-value bytes differ from stored (corruption in BLOB round-trip)")
		}
	})

	t.Run("key collisions keep only the last write", func(t *testing.T) {
		key := "stress/boundary/collision"
		const writes = 300
		var last []byte
		for i := 0; i < writes; i++ {
			last = []byte(fmt.Sprintf("collision-write-%d", i))
			if err := s.Set(ctx, key, last, time.Hour); err != nil {
				t.Fatalf("Set collision %d: %v", i, err)
			}
		}
		got, outcome, err := s.Get(ctx, key)
		if err != nil {
			t.Fatalf("Get collision: %v", err)
		}
		if outcome != cache.OutcomeHit {
			t.Errorf("collision outcome=%q want hit", outcome)
		}
		if !bytes.Equal(got, last) {
			t.Errorf("collision value=%q want last write %q (ON CONFLICT overwrite broken)", got, last)
		}
	})
}
