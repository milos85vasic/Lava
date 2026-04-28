#!/usr/bin/env bash
#
# mutation.sh — SP-2 Phase 13.2 quarterly mutation-testing wrapper.
#
# Wraps `go-mutesting` (https://github.com/zimmski/go-mutesting) over
# lava-api-go/internal/... and prints a kill/survive summary.
#
# This is an INFORMATIONAL tool, not a CI gate. It is run quarterly by the
# operator (or before a major release) to surface code paths whose tests
# don't actually catch behaviour changes — i.e. bluff tests, the failure
# mode the Sixth Law was written to prevent.
#
# Usage:
#   lava-api-go/scripts/mutation.sh
#
# Outputs:
#   mutation-report-YYYYMMDD.json   in the lava-api-go/ root.
#
# Exit code is always 0 — operators read the report and decide whether
# survived mutants represent real test gaps. We do NOT wire this into
# scripts/ci.sh because mutation testing is too slow and noisy for the
# per-push gate.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAVA_API_GO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$LAVA_API_GO_ROOT"

log()  { printf '\033[1;36m[mutation]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[mutation:warn]\033[0m %s\n' "$*" >&2; }

# Ensure go is available; mutation testing without a Go toolchain is moot.
command -v go >/dev/null 2>&1 || { warn "go not installed; skipping"; exit 0; }

# Install go-mutesting if it's not already on PATH. We reach into GOPATH/bin
# explicitly because some operator shells don't have it on PATH.
GOBIN_DIR="$(go env GOBIN)"
if [[ -z "$GOBIN_DIR" ]]; then
  GOBIN_DIR="$(go env GOPATH)/bin"
fi
PATH="$GOBIN_DIR:$PATH"

if ! command -v go-mutesting >/dev/null 2>&1; then
  log "installing github.com/zimmski/go-mutesting/cmd/go-mutesting@latest"
  if ! go install github.com/zimmski/go-mutesting/cmd/go-mutesting@latest; then
    warn "go install go-mutesting failed; cannot run mutation testing"
    exit 0
  fi
fi

if ! command -v go-mutesting >/dev/null 2>&1; then
  warn "go-mutesting still not on PATH after install ($GOBIN_DIR); skipping"
  exit 0
fi

REPORT="mutation-report-$(date +%Y%m%d).json"
log "running go-mutesting over ./internal/..."
log "report → $REPORT"

# go-mutesting prints a textual report by default; we capture stdout and
# also derive a small JSON summary from its output. The full per-mutant log
# is preserved next to the JSON.
RAW_LOG="${REPORT%.json}.log"
set +e
go-mutesting ./internal/... >"$RAW_LOG" 2>&1
status=$?
set -e

# Tally counts from the log. go-mutesting prints lines like:
#   PASS "..." with checksum ...
#   FAIL "..." with checksum ...
#   The mutation score is 0.857143 (12 passed, 2 failed, 14 total)
total=$(grep -cE '^(PASS|FAIL|SKIP) ' "$RAW_LOG" 2>/dev/null || echo 0)
killed=$(grep -cE '^FAIL ' "$RAW_LOG" 2>/dev/null || echo 0)
survived=$(grep -cE '^PASS ' "$RAW_LOG" 2>/dev/null || echo 0)

# Build the JSON report with no jq dependency.
{
  printf '{\n'
  printf '  "tool": "go-mutesting",\n'
  printf '  "scope": "lava-api-go/internal/...",\n'
  printf '  "timestamp": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "exit_status": %d,\n' "$status"
  printf '  "total": %d,\n' "$total"
  printf '  "killed": %d,\n' "$killed"
  printf '  "survived": %d,\n' "$survived"
  printf '  "raw_log": "%s"\n' "$RAW_LOG"
  printf '}\n'
} >"$REPORT"

log "summary: total=$total killed=$killed survived=$survived"
log "report: $REPORT (raw log: $RAW_LOG)"
log "this is an informational quarterly tool — exit code is always 0"
exit 0
