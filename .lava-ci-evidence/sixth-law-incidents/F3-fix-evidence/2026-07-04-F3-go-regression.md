# F3 libc-fork Go regression evidence (lava-api-go)

- Date: 2026-07-04
- Host toolchain: `go version go1.26.2-X:nodwarf5 linux/amd64` (custom toolchain)
- Working dir: `lava-api-go/`
- Fork under test: `lava-api-go/third_party/modernc-libc`, wired via
  `replace modernc.org/libc => ./third_party/modernc-libc` (go.mod:293).
  Fork adds `syscall_musl_seccomp_amd64.go` (remaps Android-x86_64-seccomp-blocked
  legacy path syscalls to their *at equivalents at the `X__syscallN` dispatcher)
  plus a no-op `syscall_musl_seccomp_other.go` stub for non-amd64 arches.
  Both fork files confirmed present.
- Resource limits per §6.T.2: test runs prefixed `GOMAXPROCS=2 nice -n 19`.
- No source edits, no commits/pushes, no emulator, no podman/Postgres, no sudo/su.

## PRE-EXISTING unrelated gap (NOT caused by the libc fork)
`go mod tidy` fails because `digital.vasic.llmorchestrator` (pulled transitively
via helixqa/pkg/ticket) has no replace directive. This is orthogonal to the libc
fork. `go mod tidy` was NOT run; all builds/tests below succeed via
`go build` / `go test` directly. The `go.sum: no such file or directory` hook
notes reference the repo-root path and are unrelated to lava-api-go's module.

---

## Task 1 — Multi-ABI Android compile of `modernc.org/libc`
Command per ABI: `CGO_ENABLED=0 GOOS=android GOARCH=$a go build modernc.org/libc`
(empty output + rc=0 = clean compile)

```
=== GOARCH=amd64 ===
PASS (clean, rc=0)
=== GOARCH=arm64 ===
PASS (clean, rc=0)
=== GOARCH=arm ===
PASS (clean, rc=0)
=== GOARCH=386 ===
PASS (clean, rc=0)
```

Verdict: **4/4 ABIs compile clean.** The amd64 path exercises the seccomp remap
file; arm64/arm/386 exercise the no-op `_other.go` stub. All PASS.

## Task 2 — Host build of the cshared embed
Command: `go build ./cmd/lavaapi-cshared`

```
rc=0
--- output ---

=== EMBED BUILD PASS ===
```

Verdict: **PASS** (clean, rc=0).

## Task 3 — SQLite-touching unit tests with the patched libc
Command: `GOMAXPROCS=2 nice -n 19 go test -count=1 ./internal/storage/... ./internal/mobile/... ./internal/cache/...`
(these exercise the remapped syscalls on the host, linux/amd64)

```
ok  	digital.vasic.lava.apigo/internal/storage	0.305s
ok  	digital.vasic.lava.apigo/internal/mobile	0.061s
ok  	digital.vasic.lava.apigo/internal/cache	0.003s
```

(Initial run reported the same three as `(cached)`; re-run with `-count=1` forced
fresh execution — verbatim `ok` lines above.)

Verdict: **storage / mobile / cache all `ok`.**

## Task 4 — Broader Go test sweep
Command: `GOMAXPROCS=2 nice -n 19 go test -count=1 ./internal/...` (exit 0)

```
ok  	digital.vasic.lava.apigo/internal/archiveorg	0.009s
ok  	digital.vasic.lava.apigo/internal/auth	0.004s
ok  	digital.vasic.lava.apigo/internal/cache	0.003s
ok  	digital.vasic.lava.apigo/internal/config	0.003s
ok  	digital.vasic.lava.apigo/internal/discovery	8.414s
ok  	digital.vasic.lava.apigo/internal/firebase	0.002s
ok  	digital.vasic.lava.apigo/internal/gen/client	0.003s
ok  	digital.vasic.lava.apigo/internal/gen/server	0.004s
ok  	digital.vasic.lava.apigo/internal/gutenberg	3.016s
ok  	digital.vasic.lava.apigo/internal/handlers	0.008s
ok  	digital.vasic.lava.apigo/internal/handlers/v1	6.774s
ok  	digital.vasic.lava.apigo/internal/httpx	0.007s
ok  	digital.vasic.lava.apigo/internal/jackett	2.230s
ok  	digital.vasic.lava.apigo/internal/kinozal	11.657s
ok  	digital.vasic.lava.apigo/internal/middleware	0.026s
?   	digital.vasic.lava.apigo/internal/migrations/sqlite	[no test files]
ok  	digital.vasic.lava.apigo/internal/mobile	0.042s
ok  	digital.vasic.lava.apigo/internal/nnmclub	12.555s
ok  	digital.vasic.lava.apigo/internal/observability	0.333s
ok  	digital.vasic.lava.apigo/internal/provider	0.001s
ok  	digital.vasic.lava.apigo/internal/provider/curated	0.003s
ok  	digital.vasic.lava.apigo/internal/provider/curated/bitsearch	6.280s
ok  	digital.vasic.lava.apigo/internal/provider/curated/knaben	6.280s
ok  	digital.vasic.lava.apigo/internal/provider/curated/nyaa	6.290s
ok  	digital.vasic.lava.apigo/internal/provider/curated/thepiratebay	0.005s
ok  	digital.vasic.lava.apigo/internal/provider/curated/tokyotosho	6.274s
ok  	digital.vasic.lava.apigo/internal/provider/curated/torrentdownloads	6.282s
ok  	digital.vasic.lava.apigo/internal/provider/curated/torrentscsv	0.005s
ok  	digital.vasic.lava.apigo/internal/provider/curated/yts	52.414s
ok  	digital.vasic.lava.apigo/internal/provider/flaresolverr	1.009s
ok  	digital.vasic.lava.apigo/internal/provider/jackettprovider	0.003s
ok  	digital.vasic.lava.apigo/internal/qa/detector	0.026s
ok  	digital.vasic.lava.apigo/internal/qa/evidence	0.006s
ok  	digital.vasic.lava.apigo/internal/qa/testbank	0.003s
ok  	digital.vasic.lava.apigo/internal/qa/ticket	0.076s
ok  	digital.vasic.lava.apigo/internal/qa/validator	0.010s
ok  	digital.vasic.lava.apigo/internal/ratelimit	0.005s
ok  	digital.vasic.lava.apigo/internal/router	0.014s
ok  	digital.vasic.lava.apigo/internal/rutracker	16.155s
ok  	digital.vasic.lava.apigo/internal/server	0.185s
ok  	digital.vasic.lava.apigo/internal/storage	0.251s
ok  	digital.vasic.lava.apigo/internal/version	0.001s
=== EXIT: 0 ===
```

Programmatic tally of the captured output:
```
ok packages: 41
FAIL packages: 0
no-test packages: 1
any error/panic: 0
```

Breakdown (honest classification):
- **Real pass: 41 packages `ok`.**
- **No-test: 1** (`internal/migrations/sqlite` — `[no test files]`, not a failure).
- **External-dep-gated (missing Postgres/podman): 0.** No package was skipped or
  failed for a missing external dependency; the whole sweep exited 0 without
  starting podman/Postgres. (The integration-gated tests are behind
  `-Pintegration`/build tags and were simply not selected — none surfaced as a
  connection failure.)
- **Real (code) failures: 0.**

## Task 5 — go vet
Command: `GOMAXPROCS=2 nice -n 19 go vet ./internal/storage/... ./internal/mobile/...`

```
rc=0
--- vet output (empty = clean) ---

--- end ---
```

Verdict: **clean** (rc=0, no diagnostics).

---

## OVERALL VERDICT: NO REGRESSION
- Multi-ABI Android compile: 4/4 clean (amd64/arm64/arm/386).
- Host cshared embed build: PASS.
- SQLite-touching tests (storage/mobile/cache): all `ok` (fresh, uncached).
- internal/... sweep: 41 ok / 1 no-test / 0 external-dep-gated / 0 real failures (exit 0).
- go vet (storage, mobile): clean.

The libc fork does NOT regress the Go API service on the host (linux/amd64) or in
cross-ABI Android compilation. The only observed anomaly is the PRE-EXISTING,
unrelated `go mod tidy` gap (`digital.vasic.llmorchestrator` missing replace),
which is independent of the libc fork and did not block any build or test.
