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

## Clauses 6.G and 6.H (added 2026-05-04)

- **Clause 6.G (End-to-End Provider Operational Verification).**
  Today this module exposes only the rutracker handlers. The
  user-facing surface IS the HTTP API itself, so 6.G binds at the
  endpoint level: every endpoint advertised by `api/openapi.yaml`
  MUST have a real-stack test that confirms a real client can
  complete the flow end-to-end (real Gin engine, real Postgres in
  podman, real upstream tracker over the network where applicable).
  An endpoint declared in the spec but unable to complete its flow
  against the real stack is a constitutional violation, irrespective
  of unit-test coverage. Becomes load-bearing on any future
  multi-tracker bridge.

- **Clause 6.H (Credential Security Inviolability).** No tracker
  username, password, API key, signing key, JWT secret, or database
  credential shall ever appear in any tracked file (`.go`, `.sql`,
  `.yaml`, `.yml`, `.md`, `.sh`, `Makefile`, …). Credentials come
  from a gitignored `.env` or a local secrets manager at runtime.
  The Auth-Token redaction rule (CONSTITUTION §Module-specific
  rules) is necessary but not sufficient — the credential must
  never reach a tracked file in the first place.
  `scripts/check-constitution.sh` enforces this at pre-push;
  introducing a credential pattern fails the push.

## Clauses 6.I and 6.J (added 2026-05-04, inherited per 6.F)

- **Clause 6.I — Multi-Emulator Container Matrix as Real-Device Equivalent** — see root `/CLAUDE.md` §6.I. Real-stack verification, where this service's work requires it (per 6.G clause 5 / Sixth Law clause 5 / Seventh Law clause 3), is satisfied by: (a) the project's container-bound multi-emulator matrix when the Android client surface is exercised through this service; (b) a real Postgres instance in podman/docker for any persistence-touching path; (c) a real HTTP/3 + HTTP/2 socket via the actual `cmd/lava-api-go` binary for any transport-touching path. Mocks of these boundaries are forbidden as the gating signal. Per-stack attestation rows go in `.lava-ci-evidence/<tag>/real-device-verification.md` (or the Go-side equivalent). A single passing test is NOT the gate.
- **Clause 6.J — Anti-Bluff Functional Reality Mandate** — see root `/CLAUDE.md` §6.J. Every test, every contract test, and every CI gate touched by this service MUST do exactly one job: confirm the feature it claims to cover actually works for an end user, end-to-end, on the gating matrix. CI green is necessary, never sufficient. Adding a test the author cannot execute against the real Postgres + real HTTP stack (or against the emulator container path where applicable) is itself a bluff. Tests must guarantee the product works — anything else is theatre.

## Clauses 6.K and 6.L (added 2026-05-04, inherited per 6.F)

- **Clause 6.K — Builds-Inside-Containers Mandate** — see root `/CLAUDE.md` §6.K. Every release-artifact build of this service MUST run inside the project's container-bound build path, anchored on `vasic-digital/Containers`'s build orchestration (`cmd/distributed-build` + `pkg/distribution` + `pkg/runtime`), not on the developer's bare host. Specifically: the `lava-api-go` static binary build (`cmd/lava-api-go`, `cmd/healthprobe`, `cmd/lava-migrate`) and the OCI image build (multi-stage `Dockerfile`) MUST go through Containers' build orchestration when producing a release artifact, when the output is signed, or when the output is consumed by the emulator-matrix gate. The existing `Makefile` targets (`make build`, `make image`, `make ci`) and the `Dockerfile` path become **thin Lava-side glue** invoking the Containers primitives — they remain in this service for iteration convenience but the gate path goes through Containers. Local incremental dev builds (`go build ./...`, `go test ./...` against the host toolchain) are PERMITTED for fast iteration; the constitutional gate is not. Per the 6.K-debt entry, until `Submodules/Containers/pkg/emulator/` and `Submodules/Containers/pkg/vm/` ship, the existing Makefile + Dockerfile + `scripts/ci.sh` continue to function as transitional glue; the next phase that touches release tagging MUST close 6.K-debt for this service before its tag. An `lava-api-go` binary built outside the container path and tested inside a clause-6.I container path is constitutionally suspect — green contract/parity/e2e tests against a binary the gate did not build are a bluff vector by construction.
- **Clause 6.L — Anti-Bluff Functional Reality Mandate (Operator's Standing Order)** — see root `/CLAUDE.md` §6.L. Restated verbatim from 6.J because the operator has invoked this mandate FOUR TIMES in a single working day; the repetition itself is the forensic record. Every test, every contract test, every parity test, every e2e test, every CI gate has exactly one job: confirm the feature works for a real user end-to-end on the gating matrix (real Gin engine, real Postgres in podman, real HTTP/3 + HTTP/2 socket, and where applicable the emulator-container path for the Android-client-facing surface). CI green is necessary, never sufficient. Tests must guarantee the product works — anything else is theatre. If you find yourself rationalizing a "small exception" — STOP. There are no small exceptions. The Internet Archive stuck-on-loading bug, the broken post-login navigation, the credential leak in C2, the bluffed C1-C8 — these are what "small exceptions" produce.

## Clause 6.M (added 2026-05-04 evening, inherited per 6.F)

- **Clause 6.M — Host-Stability Forensic Discipline** — see root `/CLAUDE.md` §6.M. Every perceived-instability event during a session that touches this service MUST be classified into Class I (verifiable host event), Class II (resource pressure), or Class III (operator-perceived without forensic evidence) AND audited via the 7-step forensic protocol (uptime+who, journalctl logind events, kernel critical events, free -h, df -h, forbidden-command grep across tracked files, container state inventory). Findings recorded under `.lava-ci-evidence/sixth-law-incidents/<date>-<slug>.json`. **Container-runtime safety analysis (recorded once in root §6.M, referenced forever):** rootless Podman has NO host-level power-management privileges; rootful Docker is not installed on the operator's primary host. The `lava-api-go` container — whether running locally via `make run-container` or in podman-compose — is a session-scoped service and cannot cause Class I host events. The container's Postgres dependency, image-export step in `make image`, and integration-test database lifecycle are all session-scoped operations. A perceived-instability event during a test run touching this service without an audit record is itself a Seventh Law violation under clause 6.J ("tests must guarantee the product works" — applied recursively to incident response). When this service is run standalone (without Lava in the parent dir), incidents are recorded at `lava-api-go/.evidence/host-stability/<date>.json` with cross-reference to the consuming Lava project's `.lava-ci-evidence/sixth-law-incidents/` if the session also exercised Lava code paths.

## Clause 6.N (added 2026-05-05, inherited per 6.F)

- **Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage** — see root `/CLAUDE.md` §6.N. Beyond the 2-4 week phase-end baseline, bluff hunts fire IN-cycle on three triggers: (1) per operator anti-bluff-mandate invocation (first/day full 5+2, subsequent same-day lighter 1-2 file incident-response), (2) per matrix-runner/gate change (pre-push enforced via §6.N-debt — owed), (3) per phase-gating attestation file added (pre-push enforced via §6.N-debt — owed). Bluff hunts MUST sample production code (2 files per phase from gate-shaping code + 0-2 from broader CI-touched code) using the conceptual filter "would a bug here be invisible to existing tests?". For this service specifically: gate-shaping code includes `tests/contract/`, `tests/parity/`, `tests/integration/` plus the production handlers they cover. The next phase that touches `scripts/check-constitution.sh` MUST close 6.N-debt by implementing the pre-push hook enforcement.
