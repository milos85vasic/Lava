# lava-api-go — Constitution

This module inherits the root project's constitutional rules. Modifying them in this module is forbidden; submodule constitutions may add stricter rules but MUST NOT relax these.

## Inherited rules (non-negotiable)

- **Sixth Law — Real User Verification** — see root `/CLAUDE.md`. Every test traverses the production code path, MUST be provably falsifiable (deliberate-mutation rehearsal recorded in PR body), MUST primary-assert on user-visible state, NOT on mock interaction counts. Cross-backend parity (test type 5) is the load-bearing release gate.
- **Seventh Law — Anti-Bluff Enforcement** — see root `/CLAUDE.md` §Seventh Law. All seven clauses (Bluff-Audit stamp, real-stack gate, pre-tag attestation, forbidden patterns, recurring bluff hunt, bluff discovery protocol, inheritance) apply to every commit touching `*_test.go` or user-facing features in this module.
- **Clause 6.G — End-to-End Provider Operational Verification** — see root `/CLAUDE.md` §6.G. Inherited per 6.F. Today this module exposes only the rutracker handlers, so the user-facing surface is the HTTP API itself. Every endpoint advertised by the OpenAPI spec MUST have a real-stack test (real Gin engine, real Postgres in podman, real upstream tracker over the network where applicable) that confirms a real client can complete the flow end-to-end. An endpoint that is declared in the spec but cannot complete its flow against the real stack is a constitutional violation, irrespective of unit-test coverage. The clause becomes load-bearing on any future multi-tracker bridge work.
- **Clause 6.H — Credential Security Inviolability** — see root `/CLAUDE.md` §6.H. Directly applicable. No tracker username, password, API key, signing key, JWT secret, or database credential shall ever appear in any `.go`, `.sql`, `.yaml`, `.yml`, `.md`, `.sh`, `Makefile`, or other tracked file. Credentials are read at runtime from environment variables (loaded from a gitignored `.env` or a local secrets manager). The Auth-Token redaction rule (§Module-specific rules) is necessary but not sufficient — the credential MUST also never reach a tracked file. `scripts/check-constitution.sh` enforces this at pre-push.
- **Local-Only CI/CD** — see root `/CLAUDE.md`. No `.github/workflows`, `.gitlab-ci.yml`, etc. `scripts/ci.sh` is the single local entry point.
- **Decoupled Reusable Architecture** — see root `/CLAUDE.md`. Lava-domain only: `internal/auth`, `internal/handlers`, `internal/rutracker`, `cmd/healthprobe`. Everything else is thin glue around vasic-digital submodules.

## Module-specific rules

- The module MUST refuse to start if Postgres is unreachable (P1 hard dependency, spec §7).
- The `/metrics` endpoint MUST be on a separate localhost-only listener; never on the public 8443 listener (spec §10.1).
- The `Auth-Token` header MUST be redacted from every log, span attribute, and audit field (the SHA-256 hash goes to `request_audit.auth_realm_hash` only).
- The cross-backend parity test (`tests/parity/`) is the load-bearing acceptance gate. A green parity suite means the Go API is byte-equivalent to the Ktor proxy on every supported request shape.
- Every release tag (`Lava-API-Go-X.Y.Z-NNNN`) requires a recorded entry in `.lava-ci-evidence/` from `scripts/pretag-verify.sh` against the exact commit being tagged.
