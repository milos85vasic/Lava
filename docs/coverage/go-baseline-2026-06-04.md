# lava-api-go — Go coverage baseline — 2026-06-04

Completeness program Phase 2B. Captured via `make cover`
(`GOMAXPROCS=2 go test ./... -coverprofile=coverage.out -covermode=atomic`).

Host: darwin/arm64, Go 1.26.2.

## Scope of this rollup

This is the **default (no-external-deps) rollup**: unit + contract + e2e + parity
tests that run against the host toolchain. The real-Postgres integration tests
(`tests/integration`, gated behind `-Pintegration` / podman) are **excluded**
from this number — they require a live Postgres container and are not part of
the default `go test ./...` surface. The real coverage of DB-touching paths is
higher than the headline once integration runs.

## Headline

**Overall: 44.5% of statements.**

The overall number is dragged down by `cmd/*` and the generated
`internal/gen/*` packages (0% — they are `oapi-codegen` output / thin
main-package entrypoints exercised only by e2e binaries). The Lava-domain
`internal/*` business packages sit substantially higher (see per-package below).

## Per-package (statements)

| Package | Coverage |
|---------|---------:|
| internal/provider | 100.0% |
| internal/config | 93.9% |
| internal/auth | 93.3% |
| internal/qa/ticket | 93.2% |
| internal/handlers | 93.1% |
| internal/qa/validator | 92.5% |
| internal/ratelimit | 90.0% |
| internal/server | 90.1% |
| internal/qa/evidence | 87.9% |
| internal/firebase | 83.3% |
| internal/qa/detector | 82.9% |
| internal/gutenberg | 82.4% |
| internal/mobile | 82.2% |
| internal/archiveorg | 81.9% |
| internal/observability | 81.7% |
| internal/rutracker | 76.8% |
| internal/cache | 69.7% |
| internal/storage | 65.7% |
| internal/discovery | 57.1% |
| internal/handlers/v1 | 53.5% |
| internal/nnmclub | 49.5% |
| internal/kinozal | 48.9% |
| cmd/healthprobe | 42.9% |
| internal/middleware | 33.9% |
| cmd/lava-api-go | 0.0% (entrypoint; covered via e2e binary, not unit) |
| internal/gen/client | 0.0% (generated) |
| internal/gen/server | 0.0% (generated) |
| internal/router | 0.0% (wiring; exercised by e2e) |

## Notable lows worth raising in a later cycle

- `internal/middleware` (33.9%) and `internal/handlers/v1` (53.5%) are
  Lava-domain request-path code; the lowest business-logic coverage in the
  module.
- `internal/kinozal` (48.9%) and `internal/nnmclub` (49.5%) — tracker scrapers
  whose deeper paths need real-tracker (`-PrealTrackers`) runs.

## Two pre-existing test failures during the rollup

The rollup completed and wrote `coverage.out`, but two packages were RED. Both
reproduce on the clean baseline (verified by stashing the Phase 2B change) — they
are pre-existing, not regressions:

- `tests/contract` → `TestAuthFieldName_NoLiteralInProductionGoSource` (a §6.R
  literal `"Lava-Auth"` in `internal/mobile/mobile.go`).
- `internal/qa/detector` → `TestCheckGoProcessByPID_Alive` (fakeRunner bypassed).

See `docs/security/2026-06-04-golangci-triage.md` for detail.

## Evidence

- `.lava-ci-evidence/completeness-program/coverage/go-baseline-2026-06-04/coverage.out`
- `.lava-ci-evidence/completeness-program/coverage/go-baseline-2026-06-04/coverage-func.txt`
- `.lava-ci-evidence/completeness-program/coverage/go-baseline-2026-06-04/test-run.log`
- `.lava-ci-evidence/completeness-program/coverage/go-baseline-2026-06-04/staticcheck-baseline.txt`
