#!/usr/bin/env bash
# tests/codegraph/lib.sh — shared helpers for the CodeGraph anti-bluff
# verification suite. Sourced by every tests/codegraph/test_*.sh and invoked
# through scripts/verify-codegraph.sh.
#
# ANTI-BLUFF CONTRACT (Lava CLAUDE.md §6.J / §6.L, Sixth & Seventh Laws):
#   Every assertion in this suite is on REAL, observed codegraph output against
#   the REAL indexed Lava codebase. No mocks, no stubs. The chief assertion of
#   each test is on user-/operator-visible data (a real symbol's real file
#   path, a real MCP JSON-RPC response, a real agent answer). The suite proves
#   its own falsifiability in test_06_falsifiability.sh: when codegraph is
#   deliberately broken, layers 01-03 FAIL. A test that cannot be made to fail
#   by breaking the thing it verifies is a bluff test by definition.

set -u

# --- paths ---------------------------------------------------------------
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$LIB_DIR/../.." && pwd)"
EVIDENCE_DIR="${CODEGRAPH_EVIDENCE_DIR:-$REPO_ROOT/.lava-ci-evidence/codegraph}"
mkdir -p "$EVIDENCE_DIR"

# --- colors (auto-off when stdout is not a tty) --------------------------
if [ -t 1 ]; then
  C_RED=$'\033[0;31m'; C_GRN=$'\033[0;32m'; C_YEL=$'\033[0;33m'
  C_DIM=$'\033[2m';    C_NC=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_DIM=""; C_NC=""
fi

# --- per-file result counters -------------------------------------------
_T_RUN=0; _T_PASS=0; _T_FAIL=0; _T_SKIP=0

log()  { printf '%s\n' "$*"; }
info() { printf '%sℹ%s %s\n' "$C_YEL" "$C_NC" "$*"; }

pass() { _T_RUN=$((_T_RUN+1)); _T_PASS=$((_T_PASS+1)); printf '%s✓ PASS%s %s\n' "$C_GRN" "$C_NC" "$*"; }
fail() { _T_RUN=$((_T_RUN+1)); _T_FAIL=$((_T_FAIL+1)); printf '%s✗ FAIL%s %s\n' "$C_RED" "$C_NC" "$*"; }
skip() { _T_RUN=$((_T_RUN+1)); _T_SKIP=$((_T_SKIP+1)); printf '%s‒ SKIP%s %s\n' "$C_YEL" "$C_NC" "$*"; }

# assert_contains <haystack> <needle> <description>
assert_contains() {
  if printf '%s' "$1" | grep -qF -- "$2"; then
    pass "$3"
  else
    fail "$3 — expected to find: '$2'"
    printf '%s    actual (first 600 chars): %s%s\n' \
      "$C_DIM" "$(printf '%s' "$1" | head -c 600 | tr '\n' ' ')" "$C_NC"
  fi
}

# assert_not_contains <haystack> <needle> <description>
assert_not_contains() {
  if printf '%s' "$1" | grep -qF -- "$2"; then
    fail "$3 — did NOT expect to find: '$2'"
    printf '%s    actual (first 600 chars): %s%s\n' \
      "$C_DIM" "$(printf '%s' "$1" | head -c 600 | tr '\n' ' ')" "$C_NC"
  else
    pass "$3"
  fi
}

# assert_file_nonempty <path> <description>
assert_file_nonempty() {
  if [ -s "$1" ]; then pass "$2 ($1)"; else fail "$2 — file missing or empty: $1"; fi
}

# assert_ge <actual> <minimum> <description>   (integer >=)
assert_ge() {
  local a="${1:-0}" m="$2"
  case "$a" in (*[!0-9]*|"") a=0 ;; esac
  if [ "$a" -ge "$m" ]; then pass "$3 (got $a, need >= $m)"
  else fail "$3 — got $a, need >= $m"; fi
}

have() { command -v "$1" >/dev/null 2>&1; }

# --- real MCP protocol drivers ------------------------------------------
# Drive `codegraph serve --mcp` over real stdio JSON-RPC 2.0. The batch-pipe
# form is the observed-working interaction (see Phase 4 probe, 2026-05-20).

# mcp_list_tools — prints the space-separated tool names from tools/list.
mcp_list_tools() {
  printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"verify-codegraph","version":"1.0"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | codegraph serve --mcp -p "$REPO_ROOT" 2>/dev/null \
  | python3 -c '
import sys, json
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        m = json.loads(line)
    except Exception:
        continue
    if m.get("id") == 2:
        names = [t.get("name", "") for t in m.get("result", {}).get("tools", [])]
        print(" ".join(n for n in names if n))
'
}

# mcp_tool_call <tool_name> <json_args> — prints the tools/call result text.
mcp_tool_call() {
  local tool="$1" args="$2"
  printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"verify-codegraph","version":"1.0"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"$tool\",\"arguments\":$args}}" \
  | codegraph serve --mcp -p "$REPO_ROOT" 2>/dev/null \
  | python3 -c '
import sys, json
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        m = json.loads(line)
    except Exception:
        continue
    if m.get("id") == 3:
        r = m.get("result", {})
        for c in r.get("content", []):
            sys.stdout.write(c.get("text", ""))
'
}

# finish — print this file's summary; return 0 iff zero failures.
finish() {
  printf -- '---- %s: %d run · %s%d pass%s · %s%d fail%s · %d skip ----\n' \
    "${TEST_NAME:-codegraph-test}" "$_T_RUN" \
    "$C_GRN" "$_T_PASS" "$C_NC" "$C_RED" "$_T_FAIL" "$C_NC" "$_T_SKIP"
  [ "$_T_FAIL" -eq 0 ]
}
