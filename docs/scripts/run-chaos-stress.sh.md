# `scripts/run-chaos-stress.sh`

**Purpose.** Orchestrate the §11.4.85 Stress + Chaos test suite for `lava-api-go`
and produce captured evidence (latency percentiles, error rates, recovery-time,
goroutine/FD leak guards) under `lava-api-go/tests/stress/evidence/<UTC>/`.

**Constitutional basis.** HelixConstitution §11.4.85 (Stress + Chaos Test
Mandate) + §11.4.98 (full-automation re-runnable live tests with real evidence) +
Lava §6.J/§6.L anti-bluff. The suite is outcome-based: it records p50/p95/p99 for
sustained load (N≥100 or ≥30s), asserts all-complete for concurrency (N≥10), and
asserts recovery-on-user-visible-state after fault injection.

## Usage

```bash
# Host-runnable stress + in-process chaos (no Postgres, no sudo, no emulator):
scripts/run-chaos-stress.sh

# Phase 1.b — real-Postgres chaos (C3 Postgres-kill + C4b pool-exhaustion) via podman:
scripts/run-chaos-stress.sh --with-podman
```

Under the hood it runs `go test -tags stress ./tests/stress/` in `lava-api-go/`.

## What runs vs. what is operator-gated

| Test | Dimension | Host-runnable now |
|------|-----------|-------------------|
| S1 sustained load | stress | yes |
| S2 concurrent contention | stress | yes |
| C1 fault-inject + recovery | chaos | yes |
| C2 latency injection | chaos | yes |
| C4a rate-limit trip | chaos | yes |
| C5 malformed input | chaos | yes |
| C3 Postgres-kill | chaos | `--with-podman` (Phase 1.b) |
| C4b pgx-pool exhaustion | chaos | `--with-podman` (Phase 1.b) |
| Phase 2 Android chaos | chaos | §6.X-debt (darwin/arm64 no KVM/HVF) |

Operator-gated tests record `ran=false, status=OPERATOR_GATED` with NO fabricated
metrics (§6.J). The curated phase-1 proof is `docs/chaos-stress/EVIDENCE-phase1.md`.

## Evidence

Each run writes `lava-api-go/tests/stress/evidence/<UTC>/stress-chaos.{json,md}`
(gitignored — regenerable). The design + a curated sample live under
`docs/chaos-stress/`.

## §11.4.74 catalogue note

Chaos *targets* reuse the `recovery` / `ratelimiter` / `observability` submodule
capabilities (e.g. `digital.vasic.recovery/pkg/facade`); the suite builds only the
load-driver + percentile-recorder glue, reimplementing no production capability.
