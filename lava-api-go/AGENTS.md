# AGENTS.md — lava-api-go

Local CI: `scripts/ci.sh`. Single source of truth.

## Tools and gates
- `go vet ./...`, `go build ./...`, `go test -race -count=1 ./...`
- `oapi-codegen` invariant (regenerate; assert empty diff)
- `go mod tidy` invariant (sha256 before/after)
- Fuzz: `go test -fuzz=. -fuzztime=30s` per package with `Fuzz*` tests
- gosec, govulncheck, trivy
- Quarterly: `scripts/mutation.sh` (go-mutesting)
- Pre-tag: `scripts/pretag-verify.sh` records `.lava-ci-evidence/<commit>.json`

## Workflow
1. Branch off `master` of the parent repo (Lava monorepo); the Go module lives at `lava-api-go/`.
2. Modify `api/openapi.yaml` first if changing wire shape.
3. Run `make generate` to update `internal/gen/`.
4. Implement the change.
5. `scripts/ci.sh` until green.
6. For every test added/modified: run the falsifiability rehearsal (see `CLAUDE.md`). Record the result in the commit body.
7. Commit. Push to `master` of the parent repo on github + gitflic + gitlab + gitverse.

## Things to avoid
- Adding hosted-CI configuration (forbidden; constitutional).
- Importing Lava-Android-specific code (Android lives in `app/`; Go API has no Android dep).
- Re-implementing functionality that exists in a vasic-digital submodule (Decoupled Reusable rule).
- Mocking internal Lava code in non-unit tests (Sixth Law clause 2).

## Host Machine Stability Directive
Per `/CLAUDE.md` and propagated through `Submodules/*/CLAUDE.md`: never run commands that suspend, hibernate, sign-out, or kill the user session. Cap test parallelism (`GOMAXPROCS=2`, `nice -n 19` are recommended).

## SP-3a-bridge expectations (added 2026-04-30)

The Kotlin-side multi-tracker SDK shipped with SP-3a binds the Go-side
rutracker bridge work (planned post-SP-2) to root constitutional
clauses 6.D, 6.E, and 6.F:

- **6.D Behavioral Coverage Contract.** Every Go interface added to
  mirror the Kotlin tracker SDK shape (e.g. a Go `tracker.Client` or
  equivalent) MUST have real-stack test coverage at the same surfaces
  a real client touches. Coverage exemptions go in
  `docs/superpowers/specs/<bridge-spec>-coverage-exemptions.md`.
- **6.E Capability Honesty.** When the Go server adds rutor (or any
  other tracker), every declared capability MUST resolve to a real
  handler. The cross-backend parity test in `tests/parity/` is the
  mechanical gate; a 501 / "Not implemented" body for a declared
  capability is a constitutional violation.
- **6.F Anti-Bluff Submodule Inheritance.** All vasic-digital
  submodules pulled in by lava-api-go inherit 6.A-6.E and the Seventh
  Law recursively. The Bluff-Audit commit-message stamp applies to
  every Go test commit (`*_test.go`).

The bridge plan is not yet written. The Go-side bridge plan link will
appear here once SP-2 ships and the bridge spec is drafted. Until then
the binding is on the contract, not on a specific PR.

## Seventh Law — Anti-Bluff Enforcement

The full authoritative text lives in root `/CLAUDE.md` and `/AGENTS.md`.
All clauses apply recursively to `lava-api-go`:

1. **Bluff-Audit Stamp.** Every `*_test.go` diff requires a `Bluff-Audit:` block in the commit message — test name, deliberate production break, observed failure, revert confirmation.
2. **Real-Stack Verification Gate.** Every user-visible feature requires real-stack verification (real HTTP/3 against real providers, real Postgres with `-Pintegration=true`).
3. **Pre-Tag Real-Device Attestation.** Required per `scripts/pretag-verify.sh`.
4. **Forbidden Test Patterns.** No mocking the SUT, no `verify { mock.foo() }` as primary assertion, no `@Ignore` without tracking issue, no tests that build but never invoke the SUT.
5. **Recurring Bluff Hunt.** Each phase ends with a bluff hunt: 5 random `*_test.go` files, deliberate mutation, confirm failure. Output to `.lava-ci-evidence/bluff-hunt/<date>.json`.
6. **Bluff Discovery Protocol.** Real-user bug + green tests = Seventh Law incident. Fix commit MUST include regression test; bluff diagnosed in `.lava-ci-evidence/sixth-law-incidents/<date>.json`.
7. **Inheritance and Propagation.** Applies recursively to every submodule and every new artifact. Submodule constitutions MAY add stricter rules but MUST NOT relax.
