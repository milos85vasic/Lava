// nil_empty_parity_test.go — pins the observable nil-vs-empty-slice contract
// for the Storage backends and proves SQLite and Postgres agree on it.
//
// The concern this test exists to lock down: SQLite (BLOB) and Postgres (BYTEA)
// could diverge on what Get returns after Set with a nil value vs an empty
// non-nil slice. A divergence here would be silent — both Set calls succeed,
// both Get calls succeed — but a cache entry that round-trips as a HIT on one
// backend and a MISS on the other is exactly the "tests green, behavior
// diverges" bluff §6.J/§6.L exist to prevent. The handler layer
// (internal/cache.Client.Get) decides hit-vs-miss on `value == nil`, so the
// nil-ness of the returned slice is a USER-OBSERVABLE fact: a returned nil =>
// the upstream scraper is re-hit; a returned empty-non-nil => the cached
// (empty) body is served.
//
// DOCUMENTED CONTRACT (verified by this test):
//
//	Set(key, nil)        then Get  => OutcomeHit, returned value is non-nil, len 0
//	Set(key, []byte{})   then Get  => OutcomeHit, returned value is non-nil, len 0
//	Set(key, []byte("x"))then Get  => OutcomeHit, returned value == []byte("x")
//
// REAL DIVERGENCE THIS TEST FOUND (and the fix): both backends store the
// `value` column as NOT NULL (SQLite `BLOB NOT NULL`, Postgres `BYTEA NOT
// NULL`). The underlying drivers bind a Go nil []byte as SQL NULL, NOT as an
// empty blob — so an un-normalized Set(key, nil) FAILS the NOT NULL constraint
// on BOTH backends ("NOT NULL constraint failed: response_cache.value" on
// modernc/sqlite; the equivalent on pgx/Postgres). This test surfaced that as
// a Set error on the SQLite leg. The minimal fix normalizes nil -> []byte{} at
// the Set boundary of each backend so Set(key, nil) succeeds and round-trips
// identically to Set(key, []byte{}):
//   - sqliteStorage.Set: `if value == nil { value = []byte{} }`
//   - internal/cache.Client.Set (the Lava-owned Postgres wrapper): same guard,
//     BEFORE the value reaches pgcache/pgx.
//
// On read, both backends return a non-nil empty slice for the stored empty
// blob (SQLite via the `value := []byte{}` init + nil guard in Get; Postgres
// via pgx scanning a zero-length BYTEA into a non-nil empty slice), and
// cache.Client.Get classifies `v != nil` => OutcomeHit. So both converge on
// "stored nil/empty => HIT with a non-nil empty slice".
//
// FALSIFIABILITY (Sixth Law clause 2): remove the `if value == nil { value =
// []byte{} }` guard from sqliteStorage.Set. Set(key, nil) then hits the NOT
// NULL constraint and the test FAILS at:
//
//	Set: storage: sqlite set: constraint failed: NOT NULL constraint failed: response_cache.value (1299)
//
// (Verified: this is the exact failure the un-normalized code produced before
// the fix.) Restoring the guard makes Set succeed and the contract holds.
package storage

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.lava.apigo/internal/cache"
)

// nilEmptyCase is one row of the observable nil-vs-empty contract.
type nilEmptyCase struct {
	name       string
	stored     []byte // the value passed to Set
	wantBytes  []byte // the bytes Get must return (compared with bytes.Equal)
	wantNonNil bool   // whether the returned slice must be non-nil (hit must not look like a miss)
}

// nilEmptyContractCases is the canonical contract every backend must satisfy.
// bytes.Equal treats nil and []byte{} as equal, so wantNonNil carries the
// extra nil-ness assertion that bytes.Equal alone would miss — and nil-ness is
// the load-bearing fact (cache.Client.Get keys hit-vs-miss on it).
func nilEmptyContractCases() []nilEmptyCase {
	return []nilEmptyCase{
		{
			name:       "nil value",
			stored:     nil,
			wantBytes:  []byte{},
			wantNonNil: true, // stored-nil must round-trip as a HIT (non-nil empty), not a MISS
		},
		{
			name:       "empty non-nil slice",
			stored:     []byte{},
			wantBytes:  []byte{},
			wantNonNil: true,
		},
		{
			name:       "single zero byte (non-empty)",
			stored:     []byte{0x00},
			wantBytes:  []byte{0x00},
			wantNonNil: true, // a 1-byte payload of NUL must survive intact, not collapse to empty
		},
		{
			name:       "ordinary payload",
			stored:     []byte("real-payload"),
			wantBytes:  []byte("real-payload"),
			wantNonNil: true,
		},
	}
}

// runNilEmptyContract drives the table against one freshly-constructed Storage.
// newStore MUST return an empty store. Asserts on the REAL bytes + nil-ness Get
// returns (user-observable hit-vs-miss state), never on call counts.
func runNilEmptyContract(t *testing.T, backend string, newStore func() Storage) {
	t.Helper()
	ctx := context.Background()
	for _, tc := range nilEmptyContractCases() {
		tc := tc
		t.Run(backend+"/"+tc.name, func(t *testing.T) {
			s := newStore()
			key := "nil-empty/" + tc.name
			if err := s.Set(ctx, key, tc.stored, time.Minute); err != nil {
				t.Fatalf("Set: %v", err)
			}
			got, outcome, err := s.Get(ctx, key)
			if err != nil {
				t.Fatalf("Get: %v", err)
			}
			// Primary user-visible assertion 1: the entry is a HIT. A stored
			// value (even empty) must be served from cache, not fall through to
			// upstream. If this is a MISS, the backend silently re-hits the
			// real scraper on every request for an empty-bodied cache entry.
			if outcome != cache.OutcomeHit {
				t.Fatalf("outcome=%q want %q (stored value must round-trip as a HIT)", outcome, cache.OutcomeHit)
			}
			// Primary user-visible assertion 2: the bytes are exactly what was
			// stored (NUL-byte payloads must not collapse to empty).
			if !bytes.Equal(got, tc.wantBytes) {
				t.Errorf("value=%v (len %d) want %v (len %d)", got, len(got), tc.wantBytes, len(tc.wantBytes))
			}
			// Primary user-visible assertion 3: nil-ness. cache.Client.Get
			// decides hit-vs-miss on `v == nil`, so a HIT MUST return a
			// non-nil slice. A nil-but-len-0 return would be classified as a
			// MISS by the Postgres handler path — the exact silent divergence
			// this test guards.
			if tc.wantNonNil && got == nil {
				t.Errorf("%s: Get returned a nil slice; want non-nil empty (HIT must serve an empty body, not fall through to a MISS)", tc.name)
			}
		})
	}
}

// TestNilEmptyContract_SQLite runs the contract against the REAL pure-Go SQLite
// backend (fresh on-disk DB per case). Always runs in the default `go test`.
func TestNilEmptyContract_SQLite(t *testing.T) {
	runNilEmptyContract(t, "sqlite", func() Storage {
		path := filepath.Join(t.TempDir(), "nil-empty.db")
		s, err := NewSQLite(path)
		if err != nil {
			t.Fatalf("NewSQLite: %v", err)
		}
		t.Cleanup(func() { _ = s.Close() })
		return s
	})
}

// TestNilEmptyContract_Postgres runs the identical contract against the REAL
// Postgres backend (real podman Postgres via POSTGRES_TEST_URL — the same
// gating mechanism postgres_test.go + parity_test.go use). Skipped honestly
// when POSTGRES_TEST_URL is unset; the SQLite leg has already pinned the
// contract and the comment above documents WHY Postgres (BYTEA NOT NULL +
// pgx empty-slice-on-zero-length) agrees with it.
func TestNilEmptyContract_Postgres(t *testing.T) {
	url := os.Getenv("POSTGRES_TEST_URL")
	if url == "" {
		t.Skip("POSTGRES_TEST_URL not set; SQLite leg pinned the nil-vs-empty contract, Postgres leg skipped honestly (run scripts/run-test-pg.sh for the cross-backend leg — the value column is BYTEA NOT NULL, pgx returns a non-nil empty slice for a zero-length BYTEA, matching SQLite's non-nil empty HIT)")
	}
	runNilEmptyContract(t, "postgres", func() Storage {
		inner, err := pgcache.ConnectFromURL(context.Background(), &pgcache.Config{
			URL:        url,
			SchemaName: "lava_api_nil_empty_test",
			TableName:  "response_cache_t",
			GCInterval: 0,
		})
		if err != nil {
			t.Fatalf("ConnectFromURL: %v", err)
		}
		if err := inner.CreateSchema(context.Background()); err != nil {
			_ = inner.Close()
			t.Fatalf("CreateSchema: %v", err)
		}
		_, _ = inner.Underlying().Exec(context.Background(),
			`TRUNCATE lava_api_nil_empty_test.response_cache_t`)
		s := NewPostgres(inner)
		t.Cleanup(func() {
			_, _ = inner.Underlying().Exec(context.Background(),
				`DROP SCHEMA IF EXISTS lava_api_nil_empty_test CASCADE`)
			_ = s.Close()
		})
		return s
	})
}
