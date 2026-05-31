# tests/stress — Stress + Chaos suite (HelixConstitution §11.4.85, Lava equivalent)

Build-tagged `stress` so it never runs in the default `go test ./...`.

```bash
# from lava-api-go/
GOMAXPROCS=2 go test -tags stress -run TestStressChaos -v ./tests/stress/...
# or, from repo root:
scripts/run-chaos-stress.sh
scripts/run-chaos-stress.sh --with-podman   # + operator-gated C3/C4b (needs real Postgres)
```

## Dimensions (in-process, host-runnable now)

| ID | Dimension | What it asserts |
|----|-----------|-----------------|
| S1 | sustained load (500 iters) | errRate=0, records p50/p95/p99/max |
| S2 | concurrent contention (64 goroutines) | all complete, errRate=0, no FD leak, `-race`-clean |
| C1 | fault injection + recovery | clean 503 during fault (no panic/hang), 200 after clear, recovery measured |
| C2 | latency injection (5ms/req) | stays responsive, percentiles degrade gracefully, no hang |
| C4a | rate-limiter trip | first K → 200, remainder → 429, deterministic |
| C5 | malformed input (1KB path) | clean 400, never 5xx/panic |

## Operator-gated (Phase 1.b — needs real Postgres under podman)

| ID | Dimension | Status |
|----|-----------|--------|
| C3 | dependency kill (Postgres) | OPERATOR_GATED — recorded `ran=false`, never faked |
| C4b | connection-pool exhaustion | OPERATOR_GATED — recorded `ran=false`, never faked |

## Evidence

Each run writes `evidence/<UTC-timestamp>/stress-chaos.{json,md}` with per-dimension
latency percentiles, status-code counts, error rates, chaos recovery metrics, FD/goroutine
leak guards, and the git SHA. No field is written unless it came from the actual run (§6.J).

See `docs/chaos-stress/DESIGN.md` for the full §11.4.85 quote and the phased plan.
