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
