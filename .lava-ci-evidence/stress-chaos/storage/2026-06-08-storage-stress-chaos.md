# Storage layer stress + chaos tests (§11.4.85)

- Date: 2026-06-08
- Module: `lava-api-go/internal/storage` (SQLite-backed `Storage` + pluggable factory)
- Commit SHA: `c409017eee5eb9408e220206b84ad0de1ee892a0`
- Files added (test-only): `internal/storage/stress_test.go`, `internal/storage/chaos_test.go`
- Command: `go test ./internal/storage/... -race -count=1 -run 'TestStress|TestChaos'`

## Scope

Real pure-Go `modernc.org/sqlite` `Storage` on fresh on-disk temp DBs (no external
service). Primary assertions on real observable state (bytes returned, hit/miss
outcome) per §6.J — never on call counts.

## STRESS

- `TestStressSustainedSetGetCycles` — 500 Set/Get cycles (≥100 mandated); correctness
  asserted every cycle (exact bytes, HIT). Latency reported + 2s catastrophic-regression
  ceiling on p99.
- `TestStressConcurrentAccessNoRace` — 16 goroutines × 200 ops = 3200 ops under `-race`;
  per-goroutine disjoint final-write namespace read back exactly post-storm. 0 errors.
- `TestStressBoundaryValues` — empty value (non-nil empty HIT), 1 MiB value (intact BLOB
  round-trip), 300-write key collision (last-writer wins).

### Latency numbers (representative run, darwin/arm64, -race)

```
SET latency  p50=624.709µs p95=1.074541ms p99=1.274083ms max=2.194792ms
GET latency  p50=286.5µs    p95=556.542µs  p99=692µs      max=932.541µs
```

(A second run without contention noise: SET p50=460µs p95=672µs p99=898µs; GET p50=235µs
p95=320µs p99=384µs.)

## CHAOS (graceful, no panic — recoverGuard guards every fault path)

| Test | Fault injected | Outcome |
|------|----------------|---------|
| `TestChaosNilAndEmptyInputs` | nil value, empty key, get/invalidate of unset key | PASS — nil rejected gracefully (surfaced error, no panic — see FLAGGED FINDING); empty key valid; unset get=miss, invalidate=no-op |
| `TestChaosClosedDBHandle` | ops after Close() | PASS — Get→(nil, OutcomeBypass, err); Set/Invalidate→err; no panic; Close idempotent |
| `TestChaosContextCancellationMidOp` | already-cancelled context | PASS — no panic; control op on live ctx still succeeds (handle not wedged) |
| `TestChaosCorruptDatabaseFile` | non-SQLite junk bytes at path | PASS — newSQLiteStorage returns err + nil Storage; no panic |
| `TestChaosLockedDatabaseGraceful` | two handles on same file (WAL/busy_timeout lock arbitration) | PASS — no panic; cross-handle write visibility holds |
| `TestChaosDiskFullSimulation` | disk-full | SKIP-with-reason — hermetic disk-full needs a size-capped volume (loopback/cgroup/tmpfs quota) unavailable without §6.U-forbidden privileges; belongs in the §11.4.85 Containers-VM harness. Honest SKIP per §11.4.3, not a faked PASS. |

## FLAGGED FINDING (real, not silently fixed)

`Set(ctx, key, nil, ttl)` returns
`storage: sqlite set: constraint failed: NOT NULL constraint failed: response_cache.value (1299)`
because a nil Go `[]byte` binds to SQL NULL against the NOT NULL `value` column.
Meanwhile `Set(ctx, key, []byte{}, ttl)` (empty-but-non-nil) succeeds and round-trips as
a non-nil empty HIT (documented; covered by `TestSQLiteZeroLengthValueRoundTrip`).

This is an inconsistency between nil and empty-non-nil values and a candidate
cross-backend PARITY gap (Postgres BYTEA may treat NULL vs empty differently). It is NOT
a crash — `Set` returns a clean wrapped error, which is graceful. Per the task scope the
production code was NOT changed; the chaos test asserts and documents the actual behavior
and will fire if production behavior changes (so any future parity fix updates the test in
lockstep). Recommend a follow-up parity decision: either coerce nil→empty in `Set`, or
document nil as an unsupported input across both backends.

## Bluff-Audit

```
Bluff-Audit: TestChaosClosedDBHandle (internal/storage/chaos_test.go)
  Mutation: internal/storage/sqlite.go Get() — changed the real-error return from
            `return nil, cache.OutcomeBypass, err` to `return nil, cache.OutcomeMiss, err`
            (a broken fault handler: a real DB error would be reported as a cache miss
            instead of bypass, so the handler would NOT fall through to upstream).
  Observed-Failure: chaos_test.go:142: Get on closed DB outcome="miss" want "bypass"
                    (so the handler falls through to upstream)
                    --- FAIL: TestChaosClosedDBHandle (0.01s)
  Reverted: yes (git diff --stat internal/storage/sqlite.go empty after revert; full
            suite green afterward)
```

## Verbatim `go test -race` output

```
=== RUN   TestChaosNilAndEmptyInputs
=== RUN   TestChaosNilAndEmptyInputs/nil_value_is_rejected_gracefully_(no_panic),_not_silently_mangled
=== RUN   TestChaosNilAndEmptyInputs/empty_key_is_a_valid_distinct_key
=== RUN   TestChaosNilAndEmptyInputs/get/invalidate_of_empty/never-set_keys_are_graceful_misses
--- PASS: TestChaosNilAndEmptyInputs (0.02s)
    --- PASS: TestChaosNilAndEmptyInputs/nil_value_is_rejected_gracefully_(no_panic),_not_silently_mangled (0.00s)
    --- PASS: TestChaosNilAndEmptyInputs/empty_key_is_a_valid_distinct_key (0.00s)
    --- PASS: TestChaosNilAndEmptyInputs/get/invalidate_of_empty/never-set_keys_are_graceful_misses (0.00s)
=== RUN   TestChaosClosedDBHandle
--- PASS: TestChaosClosedDBHandle (0.01s)
=== RUN   TestChaosContextCancellationMidOp
--- PASS: TestChaosContextCancellationMidOp (0.02s)
=== RUN   TestChaosCorruptDatabaseFile
--- PASS: TestChaosCorruptDatabaseFile (0.00s)
=== RUN   TestChaosLockedDatabaseGraceful
--- PASS: TestChaosLockedDatabaseGraceful (0.03s)
=== RUN   TestChaosDiskFullSimulation
    chaos_test.go:306: disk-full simulation requires a size-capped volume ... SKIP-with-reason per §11.4.3 ...
--- SKIP: TestChaosDiskFullSimulation (0.00s)
=== RUN   TestStressSustainedSetGetCycles
    stress_test.go:109: STRESS sustained cycles=500
    stress_test.go:110:   SET latency  p50=624.709µs p95=1.074541ms p99=1.274083ms max=2.194792ms
    stress_test.go:112:   GET latency  p50=286.5µs p95=556.542µs p99=692µs max=932.541µs
--- PASS: TestStressSustainedSetGetCycles (0.53s)
=== RUN   TestStressConcurrentAccessNoRace
    stress_test.go:200: STRESS concurrent goroutines=16 opsPerG=200 total=3200 — 0 errors, all final writes intact
--- PASS: TestStressConcurrentAccessNoRace (3.28s)
=== RUN   TestStressBoundaryValues
=== RUN   TestStressBoundaryValues/empty_value_round-trips_as_a_hit
=== RUN   TestStressBoundaryValues/large_value_round-trips_intact
=== RUN   TestStressBoundaryValues/key_collisions_keep_only_the_last_write
--- PASS: TestStressBoundaryValues (0.17s)
    --- PASS: TestStressBoundaryValues/empty_value_round-trips_as_a_hit (0.00s)
    --- PASS: TestStressBoundaryValues/large_value_round-trips_intact (0.02s)
    --- PASS: TestStressBoundaryValues/key_collisions_keep_only_the_last_write (0.13s)
PASS
ok  	digital.vasic.lava.apigo/internal/storage	5.501s
```
