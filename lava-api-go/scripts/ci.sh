#!/usr/bin/env bash
#
# scripts/ci.sh — single local entry point for lava-api-go CI.
# Per CONSTITUTION.md, this module does NOT use hosted CI services.
#
# Usage:
#   scripts/ci.sh                  # full gate
#   scripts/ci.sh --quick          # skip fuzz / gosec / govulncheck / load / image
#   scripts/ci.sh --fuzz-time=2m   # override default 30s fuzz duration

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

QUICK=false
FUZZ_TIME="30s"
for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=true ;;
    --fuzz-time=*) FUZZ_TIME="${arg#--fuzz-time=}" ;;
    -h|--help) sed -n '/^# Usage/,/^# *$/p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;36m[ci]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[ci:fail]\033[0m %s\n' "$*" >&2; exit 1; }

# 1. go mod tidy invariant
#
# Run tidy, then assert no diff via `git diff --exit-code`. This is more robust
# than sha256sum because it (a) doesn't silently pass when sha256sum is missing
# from PATH, (b) doesn't depend on go.sum existing, (c) shows the actual diff
# in CI output when the invariant fails.
log "step 1/5  go mod tidy invariant"
go mod tidy
if ! git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; then
  git --no-pager diff -- go.mod go.sum
  fail "go mod tidy produced a diff; commit the tidied result"
fi

# 2. oapi-codegen invariant
#
# Regenerate server + client from api/openapi.yaml and assert no diff.
# Same `git diff --exit-code` pattern as step 1.
log "step 2/5  oapi-codegen invariant"
./scripts/generate.sh >/dev/null
if ! git diff --exit-code -- internal/gen/ >/dev/null 2>&1; then
  git --no-pager diff -- internal/gen/
  fail "oapi-codegen produced a diff; run scripts/generate.sh and commit the result"
fi

# 3. go vet
log "step 3/5  go vet ./..."
go vet ./...

# 4. go build
log "step 4/5  go build ./..."
go build ./...

# 5. go test (race-detected, single-iteration). Tests run in BOTH modes —
# --quick still runs the full unit-test suite. --quick only skips the
# heavyweight gates (fuzz, gosec, govulncheck, k6 load, image build).
log "step 5/5  go test -race -count=1 ./..."
go test -race -count=1 ./...

# Later phases append to this script as features land:
#   - fuzz                            (Phase 6+)
#   - gosec                           (Phase 13)
#   - govulncheck                     (Phase 13)
#   - load (k6)                       (Phase 10)
#   - image build + trivy             (Phase 13)
#
# Steps are added in the phase that produces the corresponding code.

if $QUICK; then
  log "ci OK (quick — only steps 1-5)"
  exit 0
fi

log "ci OK"
