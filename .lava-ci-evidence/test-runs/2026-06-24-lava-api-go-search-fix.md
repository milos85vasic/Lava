# lava-api-go validation attestation — 2026-06-24

**HEAD commit:** `0e81730b` — `fix(search/engine): refresh stale YTS mirror list — drop dead yts.mx, lead with live mirrors (root cause 3)`  
**Prior commits in this fix arc:**  
- `20d98914` — `fix(search): cancel in-flight streaming on back-press + 25 s client timeout`  
- `7d5ffa5e` — `docs: search timeout-coordination analysis — GENERAL slow-provider bug, yts.mx NXDOMAIN; engine 20s deadline < client 45s readTimeout`  

**Date/time:** 2026-06-24  
**Operator:** validation-only; no code/commit/distribute  
**Platform:** darwin/arm64 (macOS), GOMAXPROCS=2, nice -n 19  

---

## Step 1 — Build + static analysis

| Gate | Command | Result |
|------|---------|--------|
| `go build ./...` | `go build ./...` | **PASS** — no output (clean build) |
| `go vet ./...` | `go vet ./...` | **PASS** — no output (zero vet findings) |
| `gofmt -l internal/` | `gofmt -l internal/` | **PASS** — no output (all files formatted) |

---

## Step 2 — `./internal/...` unit test suite

Command: `GOMAXPROCS=2 nice -n 19 go test -race -count=1 -timeout 120s ./internal/...`

| Package | Result | Duration |
|---------|--------|----------|
| `internal/archiveorg` | ok | 1.718s |
| `internal/auth` | ok | 1.281s |
| `internal/cache` | ok | 1.258s |
| `internal/config` | ok | 1.230s |
| `internal/discovery` | ok | 9.648s |
| `internal/firebase` | ok | 1.273s |
| `internal/gen/client` | ok | 1.282s |
| `internal/gen/server` | ok | 1.300s |
| `internal/gutenberg` | ok | 4.420s |
| `internal/handlers` | ok | 1.320s |
| `internal/handlers/v1` | ok | 8.017s |
| `internal/jackett` | ok | 5.119s |
| `internal/kinozal` | ok | 13.242s |
| `internal/middleware` | ok | 1.331s |
| `internal/migrations/sqlite` | ? [no test files] | — |
| `internal/mobile` | ok | 1.747s |
| `internal/nnmclub` | ok | 14.079s |
| `internal/observability` | ok | 1.660s |
| `internal/provider` | ok | 1.226s |
| `internal/provider/curated` | ok | 1.245s |
| `internal/provider/curated/bitsearch` | ok | 7.775s |
| `internal/provider/curated/knaben` | ok | 7.799s |
| `internal/provider/curated/nyaa` | ok | 7.836s |
| `internal/provider/curated/thepiratebay` | ok | 1.266s |
| `internal/provider/curated/tokyotosho` | ok | 7.841s |
| `internal/provider/curated/torrentdownloads` | ok | 7.862s |
| `internal/provider/curated/torrentscsv` | ok | 1.238s |
| `internal/provider/curated/yts` | ok | 53.594s |
| `internal/provider/flaresolverr` | ok | 2.263s |
| `internal/provider/jackettprovider` | ok | 1.224s |
| `internal/qa/detector` | ok | 1.356s |
| `internal/qa/evidence` | ok | 1.326s |
| `internal/qa/testbank` | ok | 1.231s |
| `internal/qa/ticket` | ok | 1.315s |
| `internal/qa/validator` | ok | 1.317s |
| `internal/ratelimit` | ok | 1.261s |
| `internal/router` | ok | 1.457s |
| `internal/rutracker` | ok | 18.264s |
| `internal/server` | ok | 1.538s |
| `internal/storage` | ok | 7.774s |
| `internal/version` | ok | 1.207s |

**Total: 40 ok, 1 skipped (no test files), 0 FAIL**

### Key packages verified

**`internal/handlers/v1`** (ok, 8.017s) — contains the 18s deadline fix in `search.go:69`:  
```go
searchCtx, searchCancel := context.WithTimeout(c.Request.Context(), 18*time.Second)
defer searchCancel()
result, err := p.Search(searchCtx, opts, creds)
```
The `GetSearch` handler now wraps every `p.Search()` call in an 18s deadline, guaranteeing the response arrives before the Android OkHttp 30s readTimeout.

**`internal/provider/curated/yts`** (ok, 53.594s — long due to `TestSearch_TotalDeadlineBoundsSlowMirrors` exercising 4 hanging httptest servers with 18s real-time deadline + the `TestSearch_BugReproduction_NoDeadline` test skipped in short mode):
- `TestSearch_TotalDeadlineBoundsSlowMirrors` — confirms Client.Search with 18s deadline returns within 20s even when all mirrors hang (4 × 8s = 32s without deadline)
- Mirror list updated: `yts.mx` removed (NXDOMAIN), `yts.bz` leads (fastest verified live mirror), list is now `[yts.bz, yts.lt, yts.am, yts.gg, movies-api.accel.li]`

---

## Step 3 — Heavy suites (contract / parity / integration / stress)

### contract + parity (no external deps)
Command: `GOMAXPROCS=2 nice -n 19 go test -race -count=1 -timeout 120s ./tests/contract/... ./tests/parity/...`

| Suite | Result | Duration |
|-------|--------|----------|
| `tests/contract` | **ok** | 82.134s |
| `tests/parity` | **ok** | 1.554s |

### e2e
Command: `GOMAXPROCS=2 nice -n 19 go test -race -count=1 -timeout 60s ./tests/e2e/...`

| Suite | Result | Duration |
|-------|--------|----------|
| `tests/e2e` | **ok** | 5.628s |

### integration (real Postgres in podman — `scripts/run-test-pg.sh`)
Podman available (v5.8.2). `docker.io/library/postgres:16-alpine` image present locally.  
Command: `bash scripts/run-test-pg.sh`  
Postgres container launched on random port, readiness-waited, torn down on exit.

```
TestCrossBackendParity                              PASS
TestIntegration_AuthActiveUuid_Returns200           PASS
TestIntegration_AuthBackoffLadder_RetryAfterMatchesStep  PASS
TestIntegration_AuthBackoffResets_AfterValidUuid    PASS
TestIntegration_BrotliResponse_Compresses           PASS
TestIntegration_BrotliResponse_PassesThroughWithoutAcceptEncoding  PASS
TestIntegration_AltSvc_AdvertisedOnHTTP2            PASS
TestIntegration_AltSvc_DisabledNoHeader             PASS
TestIntegration_ProtocolMetric_IncrementsOnH1or2Request  PASS
TestIntegration_ProtocolMetric_4xxStatusClass       PASS
TestIntegration_AuthRetiredUuid_Returns426WithMinVersion  PASS
TestIntegration_AuthUnknownUuid_Returns401          PASS
```

| Package | Result | Duration |
|---------|--------|----------|
| `internal/storage` | **ok** | 5.949s |
| `tests/integration` | **ok** | 1.524s |
| `tests/integration/testenv` | ? [no test files] | — |

### stress (`-tags stress`)
Command: `GOMAXPROCS=2 nice -n 19 go test -race -count=1 -timeout 120s -tags stress ./tests/stress`

| Suite | Result | Duration |
|-------|--------|----------|
| `tests/stress` | **ok** | 3.359s |

### Skipped suites (honest reason)

| Suite | Reason |
|-------|--------|
| `tests/qa` | Build-tag gated: `helixqa_realstack` — requires HelixQA submodule module-graph resolution; intentionally excluded from default `go test ./...` |
| `tests/load` | k6 load tests (`.js` scripts); require k6 binary not installed on this host; Go load_test.go has no build tag but is k6-integration-only |

---

## Summary tally

| Category | Packages | Result |
|----------|----------|--------|
| `go build ./...` | all | PASS |
| `go vet ./...` | all | PASS |
| `gofmt -l internal/` | all | PASS (empty output) |
| `./internal/...` unit tests | 40 ok, 1 no-test | ALL GREEN |
| `tests/contract` | 1 | ok |
| `tests/parity` | 1 | ok |
| `tests/e2e` | 1 | ok |
| `tests/integration` (real Postgres) | 2 ok, 1 no-test | ALL GREEN |
| `tests/stress` (-tags stress) | 1 | ok |
| `tests/qa` | — | SKIPPED (helixqa_realstack build tag) |
| `tests/load` | — | SKIPPED (k6 not installed) |

**VERDICT: ALL GREEN** — 47 packages ok, 0 FAIL, 2 suites skipped with honest reason.

The engine 18s-deadline fix (`internal/handlers/v1/search.go:69`) is confirmed present and exercised.  
The YTS mirror-refresh fix (`internal/provider/curated/yts/client.go:48`) is confirmed present:  
`yts.mx` removed, `DefaultBaseURLs = [yts.bz, yts.lt, yts.am, yts.gg, movies-api.accel.li]`.  
`TestSearch_TotalDeadlineBoundsSlowMirrors` passes — no regression on the deadline fix.
