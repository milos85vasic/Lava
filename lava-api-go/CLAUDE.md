# CLAUDE.md — lava-api-go

Agent-facing guide for working in this module.

## Inherited rules (non-negotiable)
See `CONSTITUTION.md`. Sixth Law, Local-Only CI/CD, Decoupled Reusable Architecture.

## Tech stack
- Go 1.24+
- Gin Gonic, quic-go v0.59 (via `digital.vasic.http3`), pgx/v5
- Tests: stdlib `testing`, real Postgres in podman, real HTTP/3 client
- Local CI: `scripts/ci.sh` (no hosted CI ever)

## Layout
- `api/openapi.yaml` — D1 spec-first, source of truth.
- `internal/gen/{server,client}/` — committed `oapi-codegen` output. CI enforces "regenerate produces empty diff".
- `internal/{auth,handlers,rutracker}/` and `cmd/healthprobe/` — Lava-domain code.
- `internal/{cache,config,discovery,observability,ratelimit,server}/` — thin glue around vasic-digital submodules.
- `migrations/` — golang-migrate SQL files; applied by the one-shot `lava-migrate` compose service.
- `tests/{contract,e2e,parity,load,fixtures}/` — the higher test types per spec §11.

## When changing public API
The OpenAPI spec is the contract. Modify `api/openapi.yaml` first, regenerate, then update handlers. The cross-backend parity test will fail if the spec diverges from the Ktor proxy's wire shape — that's the protection.

## Falsifiability protocol (Sixth Law clause 2)
Every PR adding or modifying a test MUST record in its commit body:
- `Test:` the test name + file
- `Mutation:` the deliberate production-code change
- `Observed:` the exact assertion message that fired
- `Reverted:` confirmation that the final commit reflects unmutated code

## SP-3a-bridge expectations (added 2026-04-30)

When SP-2 ships its last release tag, the SP-3a-bridge follow-up plan
will refactor the Go-side rutracker handlers to mirror the Kotlin-side
multi-tracker SDK. That work is not yet planned in detail; this clause
makes the constitutional binding explicit now so the Go-side work
cannot drift from the Anti-Bluff Pact established on the Android side.

Specifically:

- **Clause 6.D (Behavioral Coverage Contract) binds the Go-side
  rutracker refactor.** Every public method of every Go interface
  added to bridge to the Kotlin SDK shape (e.g. a Go `tracker.Client`
  contract) MUST have at least one real-stack test traversing the
  same code path a real client request would trigger. The
  `tests/contract/`, `tests/e2e/`, and `tests/parity/` directories
  already enforce this for current handlers; the bridge work extends
  it. Coverage exemptions go to `docs/superpowers/specs/<bridge-spec>-
  coverage-exemptions.md`.

- **Clause 6.E (Capability Honesty) binds the Go-side tracker
  registry.** When the Go server learns to advertise multiple
  trackers (rutracker + rutor), every capability declared by a Go
  `TrackerDescriptor` MUST resolve to a real handler — no "501 Not
  Implemented" stubs behind a declared capability. The cross-backend
  parity test in `tests/parity/` is the mechanical gate.

- **Clause 6.F (Anti-Bluff Submodule Inheritance).** Every
  vasic-digital submodule pulled in by lava-api-go inherits 6.A-6.E
  recursively. Adopting an externally maintained dependency that
  does not satisfy these clauses requires forking it under
  vasic-digital/ first. The Seventh Law (Anti-Bluff Enforcement)
  inherits under the same rule; the Bluff-Audit commit-message stamp
  applies verbatim to every Go test commit (`*_test.go`).

The bridge plan lives at `docs/superpowers/plans/<TBD-after-SP-2>.md`
once written. Until then, no Go-side work that touches the bridge
shape is in scope; SP-3a Phases 0-5 explicitly exclude Go changes.
