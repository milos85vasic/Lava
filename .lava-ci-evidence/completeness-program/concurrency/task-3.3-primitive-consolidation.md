# Phase 3 Task 3.3 — Submodule-primitive consolidation decision log

Goal (Master Plan §3.3): where inline circuit-breaker/semaphore/limiter
duplicates `Submodules/Concurrency` or `Submodules/RateLimiter`, migrate to the
submodule primitive, or document why the inline version is Lava-domain-specific.

## Ground truth

- `submodules/concurrency/pkg/` and `submodules/ratelimiter/pkg/` are **Go**
  packages (`breaker`, `bulkhead`, `limiter`, `semaphore`, `pool`, … /
  `tokenbucket`, `sliding`, `ladder`, `adaptive`, …).
- **Go side — ALREADY CONSOLIDATED (no action owed).** `lava-api-go/go.mod`
  carries `replace digital.vasic.concurrency => ../submodules/concurrency` and
  `digital.vasic.ratelimiter => ../submodules/ratelimiter`; 7 production files
  import them: `cmd/lava-api-go/main.go`, `internal/auth/{backoff,middleware}.go`,
  `internal/mobile/mobile.go`, `internal/ratelimit/ratelimit.go`,
  `internal/router/router.go`, plus the integration testenv. The Go service is
  the intended consumer and is wired correctly.

## Kotlin inline primitives — finding

Three Kotlin tracker HTTP clients each carry a **near-identical inline**
`CircuitBreaker` (`failureThreshold = 3`, 30 s window, `CircuitBreakerOpenException`)
+ `Semaphore(permits = 4)` with `withPermit`:

| File | Breaker | Semaphore |
|---|---|---|
| `core/tracker/kinozal/.../http/KinozalHttpClient.kt` | yes (3 / 30 s) | `Semaphore(4)` |
| `core/tracker/nnmclub/.../http/NnmclubHttpClient.kt` | yes (3 / 30 s) | `Semaphore(4)` |
| `core/tracker/rutor/.../http/RuTorHttpClient.kt`    | yes (3 / 30 s) | `Semaphore(4)` |

This is genuine cross-module duplication (3 copies of the same ~60-line
breaker + bounded-concurrency wrapper).

## Decision

1. **Cannot consolidate the Kotlin breakers onto the existing submodules** —
   `submodules/{concurrency,ratelimiter}` are Go; the tracker clients are
   Kotlin/JVM. A language boundary, not a wiring gap. Forcing it is impossible.
2. **The Kotlin duplication is real and is the correct consolidation target,
   but as a Kotlin extraction, not a Go-submodule migration.** Per the Decoupled
   Reusable Architecture rule the shared home is a Kotlin primitive — extract a
   single `LavaCircuitBreaker` + bounded-concurrency helper (with behavioral
   tests: trip-after-N, half-open reset, permit cap) into a shared Kotlin
   location (`core:tracker:api` or a new `core:tracker:http-common`), then have
   kinozal/nnmclub/rutor consume it. (rutracker/rutor's own client predates
   these; include it in the sweep.)
3. **Scheduled for Phase 4, not forced into Phase 3.** The extraction changes 3
   production clients and needs its own TDD + behavioral tests for the shared
   primitive (the breaker is concurrency-critical — a careless extraction could
   regress the per-tracker overload protection). Doing it as a rushed Phase-3
   edit without that test scaffolding would violate the no-break guarantee.
   Tracked as a Phase-4 dedup/testability task.

## Net

- Go consolidation: **DONE** (verified, no action).
- Kotlin breaker dedup: **logged + scheduled** (Phase 4); inline versions are
  Lava-domain JVM code with no Go-submodule equivalent, kept as-is until the
  Kotlin shared primitive lands with tests.
