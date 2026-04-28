# lava-api-go — Constitution

This module inherits the root project's constitutional rules. Modifying them in this module is forbidden; submodule constitutions may add stricter rules but MUST NOT relax these.

## Inherited rules (non-negotiable)

- **Sixth Law — Real User Verification** — see root `/CLAUDE.md`. Every test traverses the production code path, MUST be provably falsifiable (deliberate-mutation rehearsal recorded in PR body), MUST primary-assert on user-visible state, NOT on mock interaction counts. Cross-backend parity (test type 5) is the load-bearing release gate.
- **Local-Only CI/CD** — see root `/CLAUDE.md`. No `.github/workflows`, `.gitlab-ci.yml`, etc. `scripts/ci.sh` is the single local entry point.
- **Decoupled Reusable Architecture** — see root `/CLAUDE.md`. Lava-domain only: `internal/auth`, `internal/handlers`, `internal/rutracker`, `cmd/healthprobe`. Everything else is thin glue around vasic-digital submodules.

## Module-specific rules

- The module MUST refuse to start if Postgres is unreachable (P1 hard dependency, spec §7).
- The `/metrics` endpoint MUST be on a separate localhost-only listener; never on the public 8443 listener (spec §10.1).
- The `Auth-Token` header MUST be redacted from every log, span attribute, and audit field (the SHA-256 hash goes to `request_audit.auth_realm_hash` only).
- The cross-backend parity test (`tests/parity/`) is the load-bearing acceptance gate. A green parity suite means the Go API is byte-equivalent to the Ktor proxy on every supported request shape.
- Every release tag (`Lava-API-Go-X.Y.Z-NNNN`) requires a recorded entry in `.lava-ci-evidence/` from `scripts/pretag-verify.sh` against the exact commit being tagged.
