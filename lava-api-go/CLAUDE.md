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
