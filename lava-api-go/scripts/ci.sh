#!/usr/bin/env bash
#
# scripts/ci.sh — single local entry point for lava-api-go CI.
# Per CONSTITUTION.md, this module does NOT use hosted CI services.
#
# Usage:
#   scripts/ci.sh                  # full gate (permissive: skip-with-warning on missing tools)
#   scripts/ci.sh --quick          # skip fuzz / gosec / govulncheck / load / image
#   scripts/ci.sh --strict         # refuse to skip on missing security tools (Phase 14 acceptance mode)
#   scripts/ci.sh --fuzz-time=2m   # override default 30s fuzz duration
#
# Per-tool bypass (env vars):
#   LAVA_CI_SKIP_GOSEC=1          # skip gosec step
#   LAVA_CI_SKIP_GOVULNCHECK=1    # skip govulncheck step
#   LAVA_CI_SKIP_TRIVY=1          # skip trivy image scan
#
# Documented acceptable findings (lower-severity gosec, third-party-only
# govulncheck) live in lava-api-go/SECURITY.md.

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

QUICK=false
STRICT=false
FUZZ_TIME="30s"
for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=true ;;
    --strict) STRICT=true ;;
    --fuzz-time=*) FUZZ_TIME="${arg#--fuzz-time=}" ;;
    -h|--help) sed -n '/^# Usage/,/^# *$/p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;36m[ci]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[ci:warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[ci:fail]\033[0m %s\n' "$*" >&2; exit 1; }

# skip_or_fail prints a "skipping <tool>: <reason>" warning and either
# returns (permissive default) or exits 1 (--strict).
skip_or_fail() {
  local tool="$1" reason="$2"
  warn "skipping $tool: $reason"
  if $STRICT; then
    fail "--strict refuses to skip $tool"
  fi
}

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

if $QUICK; then
  log "ci OK (quick — only steps 1-5)"
  exit 0
fi

# ----------------------------------------------------------------------
# Phase 13 security gates: gosec → govulncheck → trivy
#
# gosec + govulncheck hard-fail on findings (after best-effort install).
# trivy is optional: missing trivy or missing image is a clean skip.
# Each step honours LAVA_CI_SKIP_<TOOL>=1.
# ----------------------------------------------------------------------

# Pull go's bin dir into PATH so freshly-installed tools are visible.
GOBIN_DIR="$(go env GOBIN 2>/dev/null || true)"
if [[ -z "$GOBIN_DIR" ]]; then
  GOBIN_DIR="$(go env GOPATH 2>/dev/null || echo "$HOME/go")/bin"
fi
PATH="$GOBIN_DIR:$PATH"

# 6. gosec — static security scanner
if [[ "${LAVA_CI_SKIP_GOSEC:-0}" == "1" ]]; then
  warn "skipping gosec: LAVA_CI_SKIP_GOSEC=1"
else
  log "step 6  gosec -severity high -confidence high ./..."
  if ! command -v gosec >/dev/null 2>&1; then
    if go install github.com/securego/gosec/v2/cmd/gosec@latest >/dev/null 2>&1; then
      :
    else
      skip_or_fail gosec "not installed; run \`go install github.com/securego/gosec/v2/cmd/gosec@latest\`"
    fi
  fi
  if command -v gosec >/dev/null 2>&1; then
    if ! gosec -severity high -confidence high ./...; then
      fail "gosec reported HIGH severity / HIGH confidence findings (see SECURITY.md for accepted lower-severity entries)"
    fi
  fi
fi

# 7. govulncheck — known-vulnerability scanner
if [[ "${LAVA_CI_SKIP_GOVULNCHECK:-0}" == "1" ]]; then
  warn "skipping govulncheck: LAVA_CI_SKIP_GOVULNCHECK=1"
else
  log "step 7  govulncheck ./..."
  if ! command -v govulncheck >/dev/null 2>&1; then
    if go install golang.org/x/vuln/cmd/govulncheck@latest >/dev/null 2>&1; then
      :
    else
      skip_or_fail govulncheck "not installed; run \`go install golang.org/x/vuln/cmd/govulncheck@latest\`"
    fi
  fi
  if command -v govulncheck >/dev/null 2>&1; then
    if ! govulncheck ./...; then
      fail "govulncheck reported a vulnerability"
    fi
  fi
fi

# 8. trivy — container image vulnerability scan
#
# Optional: if trivy isn't installed OR the image isn't built locally, skip
# cleanly without failing ci.sh. trivy is an enhancement; gosec +
# govulncheck are the gating ones.
if [[ "${LAVA_CI_SKIP_TRIVY:-0}" == "1" ]]; then
  warn "skipping trivy: LAVA_CI_SKIP_TRIVY=1"
else
  log "step 8  trivy image --severity HIGH,CRITICAL lava-api-go:dev"
  if ! command -v trivy >/dev/null 2>&1; then
    warn "skipping trivy: not installed; install via your package manager (e.g. \`brew install trivy\`)"
    if $STRICT; then
      fail "--strict refuses to skip trivy"
    fi
  else
    # Detect image presence with whichever container tool is available.
    image_present=false
    if command -v docker >/dev/null 2>&1 && docker image inspect lava-api-go:dev >/dev/null 2>&1; then
      image_present=true
    elif command -v podman >/dev/null 2>&1 && podman image inspect lava-api-go:dev >/dev/null 2>&1; then
      image_present=true
    fi
    if ! $image_present; then
      warn "skipping trivy: image lava-api-go:dev not found locally; build via ./start.sh first"
    else
      if ! trivy image --severity HIGH,CRITICAL --exit-code 1 lava-api-go:dev; then
        fail "trivy reported HIGH/CRITICAL vulnerabilities in lava-api-go:dev"
      fi
    fi
  fi
fi

# Future appends (per phase plan):
#   - fuzz   (Phase 6+; uses FUZZ_TIME=$FUZZ_TIME)
#   - load (k6)   (Phase 10)

log "ci OK"
