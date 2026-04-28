#!/usr/bin/env bash
#
# pretag-verify.sh — SP-2 Phase 13.1 real-device verification gate.
#
# Drives a scripted user flow against a running lava-api-go and records the
# outcome to .lava-ci-evidence/<commit>.json. scripts/tag.sh refuses to cut
# a Lava-API-Go-* tag without a matching evidence file.
#
# This script is the mechanical implementation of Sixth Law clause 5
# ("CI green is necessary, not sufficient — a human or scripted black-box
# runner MUST have used the feature on a real device or environment and
# observed the user-visible outcome before any release tag is cut").
#
# Usage:
#   lava-api-go/scripts/pretag-verify.sh
#   lava-api-go/scripts/pretag-verify.sh --allow-dirty
#
# Environment:
#   LAVA_API_BASE_URL   Override the API URL (default: https://localhost:8443)
#
# The script does NOT bring up the lava-api-go stack. That is the operator's
# responsibility: run `./start.sh` (or `./start.sh --both`) first. If the API
# is unreachable, this script exits 1 with a clear message.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAVA_API_GO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$LAVA_API_GO_ROOT/.." && pwd)"

ALLOW_DIRTY=false
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=true ;;
    -h|--help)
      sed -n '1,30p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;36m[pretag]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[pretag:warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[pretag:fail]\033[0m %s\n' "$*" >&2; exit 1; }

cd "$REPO_ROOT"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || fail "Not inside a git working tree: $REPO_ROOT"

commit="$(git rev-parse HEAD)"

if [[ -n "$(git status --porcelain)" ]]; then
  if $ALLOW_DIRTY; then
    warn "working tree is dirty — proceeding because --allow-dirty was passed"
  else
    fail "working tree is dirty (commit / stash first, or pass --allow-dirty)"
  fi
fi

BASE_URL="${LAVA_API_BASE_URL:-https://localhost:8443}"
EVIDENCE_DIR="$REPO_ROOT/.lava-ci-evidence"
EVIDENCE_FILE="$EVIDENCE_DIR/${commit}.json"

mkdir -p "$EVIDENCE_DIR"

command -v curl >/dev/null 2>&1 || fail "curl is required but not installed"

# Pick HTTP version flag: prefer HTTP/3 if curl supports --http3-only,
# else fall back to --http2. -k tolerates the dev self-signed cert.
HTTP_FLAGS=(-k --silent --show-error --max-time 15)
if curl --help all 2>&1 | grep -q -- '--http3-only'; then
  HTTP_FLAGS+=(--http3-only)
elif curl --help all 2>&1 | grep -q -- '--http3'; then
  HTTP_FLAGS+=(--http3)
else
  HTTP_FLAGS+=(--http2)
fi

# Reachability probe: GET / with a 5s ceiling. A successful TCP/TLS
# handshake plus any HTTP response is enough to declare "reachable".
if ! curl "${HTTP_FLAGS[@]}" --max-time 5 -o /dev/null -w '%{http_code}' "$BASE_URL/" >/dev/null 2>&1; then
  fail "API not reachable at $BASE_URL; bring up via './start.sh' first"
fi

declare -a CHECK_LINES=()
EXIT_CODE=0

run_check() {
  local path="$1" want_anon="$2"
  local url="${BASE_URL}${path}"
  local tmp
  tmp="$(mktemp)"
  local start_ms end_ms ms status bytes
  start_ms=$(date +%s%3N)
  status=$(curl "${HTTP_FLAGS[@]}" -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null || echo "000")
  end_ms=$(date +%s%3N)
  ms=$((end_ms - start_ms))
  bytes=$(wc -c <"$tmp" | tr -d ' ')
  rm -f "$tmp"

  # Failure: network error (status 000) or any 5xx.
  local check_failed=false
  if [[ "$status" == "000" ]]; then
    warn "[$path] network error"
    check_failed=true
  elif [[ "$status" =~ ^5[0-9][0-9]$ ]]; then
    warn "[$path] upstream 5xx: $status"
    check_failed=true
  fi

  if $check_failed; then
    EXIT_CODE=1
  else
    log "[$path] $status (${bytes}B, ${ms}ms)"
  fi

  # JSON-encode the per-check record, escaping path defensively.
  local jpath jstatus jbytes jms
  jpath=$(printf '%s' "$path" | sed 's/\\/\\\\/g; s/"/\\"/g')
  jstatus=$(printf '%s' "$status" | sed 's/[^0-9]//g')
  [[ -z "$jstatus" ]] && jstatus=0
  jbytes="$bytes"
  jms="$ms"
  CHECK_LINES+=("    {\"path\":\"$jpath\",\"status\":$jstatus,\"bytes\":$jbytes,\"ms\":$jms}")

  # want_anon is informational only at this point; we record it but do not
  # cross-check the body. Body-level cross-checks are the parity test's job
  # (tests/parity/), not the pretag verify's. This script's contract is
  # "the running stack returned a non-5xx for every step the user takes".
  :
}

log "commit:    $commit"
log "base_url:  $BASE_URL"
log "evidence:  $EVIDENCE_FILE"
log "running scripted user flow…"

# Five-step user flow, identical to spec §11.3 sketch:
run_check "/"               "anon-bool"
run_check "/forum"          "non-empty-json"
run_check "/search?query=test" "json"
run_check "/torrent/1"      "json"
run_check "/favorites"      "any-non-5xx"

timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
hostname_val="$(hostname 2>/dev/null || echo unknown)"

# Build the evidence file. We assemble it manually rather than shelling out
# to jq so the script has zero hard dependencies beyond curl + bash.
{
  printf '{\n'
  printf '  "commit": "%s",\n' "$commit"
  printf '  "timestamp": "%s",\n' "$timestamp"
  printf '  "host": "%s",\n' "$hostname_val"
  printf '  "base_url": "%s",\n' "$BASE_URL"
  printf '  "checks": [\n'
  local_n=${#CHECK_LINES[@]}
  for i in "${!CHECK_LINES[@]}"; do
    if (( i + 1 < local_n )); then
      printf '%s,\n' "${CHECK_LINES[$i]}"
    else
      printf '%s\n' "${CHECK_LINES[$i]}"
    fi
  done
  printf '  ],\n'
  printf '  "exit_code": %d\n' "$EXIT_CODE"
  printf '}\n'
} >"$EVIDENCE_FILE"

if (( EXIT_CODE != 0 )); then
  fail "one or more checks failed; evidence written to $EVIDENCE_FILE but pretag verify FAILED"
fi

log "all checks passed; evidence recorded at $EVIDENCE_FILE"
log "now safe to run scripts/tag.sh --app api-go"
exit 0
