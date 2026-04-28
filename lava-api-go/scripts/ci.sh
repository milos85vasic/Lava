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
log "step 1/N  go mod tidy invariant"
_pre="$(sha256sum go.mod go.sum 2>/dev/null | sort)"
go mod tidy
_post="$(sha256sum go.mod go.sum 2>/dev/null | sort)"
[[ "$_pre" == "$_post" ]] || fail "go mod tidy produced a diff; commit the tidied result"

# 2. go vet
log "step 2/N  go vet ./..."
go vet ./...

# 3. go build
log "step 3/N  go build ./..."
go build ./...

# Later phases append to this script as features land:
#   - oapi-codegen invariant
#   - go test -race -count=1 ./...
#   - fuzz
#   - gosec
#   - govulncheck
#   - load (k6)
#   - image build + trivy
#
# Steps are added in the phase that produces the corresponding code.

if $QUICK; then
  log "ci OK (quick — only steps 1-3)"
  exit 0
fi

log "ci OK"
