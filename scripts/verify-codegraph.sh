#!/usr/bin/env bash
# scripts/verify-codegraph.sh — anti-bluff verification suite runner for the
# CodeGraph integration.
#
# Per the Local-Only CI/CD constitutional constraint, this script IS the
# codegraph quality gate — there is no hosted-CI equivalent. It runs the six
# tests/codegraph/test_*.sh layers in order, tees per-layer evidence to
# .lava-ci-evidence/codegraph/<UTC-timestamp>/, writes a summary, and exits
# non-zero if any layer FAILS.
#
# Layers:
#   01 index reality      — codegraph indexed the real Lava codebase
#   02 query correctness  — codegraph resolves real symbols to real locations
#   03 MCP protocol       — the MCP server returns real data over stdio JSON-RPC
#   04 agent connectivity — all 5 CLI agents register/connect to codegraph
#   05 agent E2E          — each agent provably USES codegraph in a real LLM turn
#   06 falsifiability     — the suite fails when codegraph is deliberately broken
#
# SKIPs (e.g. an un-authenticated agent in layer 05) are DOCUMENTED GAPS — they
# are reported prominently but do not, by themselves, fail the suite. A FAIL in
# any layer fails the suite. CI green here is necessary, never sufficient
# (§6.L): read the summary, and re-run after authenticating any skipped agent.
#
# Usage:  scripts/verify-codegraph.sh [--quick]
#   --quick   run layers 01-04 + 06 only (skip the slow LLM-driven layer 05)
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TESTS_DIR="$REPO_ROOT/tests/codegraph"
TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
RUN_DIR="$REPO_ROOT/.lava-ci-evidence/codegraph/$TS"
export CODEGRAPH_EVIDENCE_DIR="$RUN_DIR"
mkdir -p "$RUN_DIR"

if [ -t 1 ]; then
  R=$'\033[0;31m'; G=$'\033[0;32m'; Y=$'\033[0;33m'; B=$'\033[1m'; N=$'\033[0m'
else
  R=""; G=""; Y=""; B=""; N=""
fi

QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

LAYERS="test_01_index_reality test_02_query_correctness test_03_mcp_protocol test_04_agent_connectivity test_05_agent_e2e test_06_falsifiability"
[ "$QUICK" -eq 1 ] && LAYERS="test_01_index_reality test_02_query_correctness test_03_mcp_protocol test_04_agent_connectivity test_06_falsifiability"

printf '%s\n' "${B}CodeGraph anti-bluff verification suite${N}"
printf 'repo:     %s\n' "$REPO_ROOT"
printf 'evidence: %s\n' "$RUN_DIR"
printf '%s\n\n' "----------------------------------------------------------------"

# --- pre-flight ----------------------------------------------------------
preflight_ok=1
if ! command -v codegraph >/dev/null 2>&1; then
  printf '%s✗ pre-flight%s codegraph is not installed (npm install -g @colbymchenry/codegraph)\n' "$R" "$N"
  preflight_ok=0
fi
if [ ! -f "$REPO_ROOT/.codegraph/codegraph.db" ]; then
  printf '%s✗ pre-flight%s .codegraph/codegraph.db missing — run `codegraph index` first\n' "$R" "$N"
  preflight_ok=0
fi
# Backend health — codegraph must be able to actually OPEN the index. Catches
# the native-better-sqlite3-disabled / WASM-fallback-cannot-open-DB regression
# class (a brew-disturbed or stale global install) that would otherwise surface
# mid-suite as confusing "CodeGraph not initialized" errors instead of a clean
# pre-flight abort. (Encountered 2026-05-20 — see docs/CODEGRAPH.md §6.)
if [ "$preflight_ok" -eq 1 ] && ! codegraph status >/dev/null 2>&1; then
  printf '%s✗ pre-flight%s `codegraph status` fails — codegraph cannot open the index DB. The native better-sqlite3 binding may be disabled (WASM fallback). Fix: `codegraph index` to rebuild; if it persists, reinstall — `npm install -g @colbymchenry/codegraph`.\n' "$R" "$N"
  preflight_ok=0
fi
if [ "$preflight_ok" -ne 1 ]; then
  printf '%spre-flight failed — aborting%s\n' "$R" "$N"
  exit 2
fi

# --- run each layer ------------------------------------------------------
SUITE_FAIL=0
declare -a RESULTS
for t in $LAYERS; do
  f="$TESTS_DIR/$t.sh"
  log="$RUN_DIR/$t.log"
  if [ ! -f "$f" ]; then
    printf '%s✗ %s — test file missing%s\n' "$R" "$t" "$N"
    RESULTS+=("MISSING  $t")
    SUITE_FAIL=1
    continue
  fi
  printf '%s▶ running %s%s\n' "$B" "$t" "$N"
  bash "$f" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 0 ]; then
    RESULTS+=("PASS     $t")
  else
    RESULTS+=("FAIL     $t")
    SUITE_FAIL=1
  fi
  printf '\n'
done

# --- aggregate SKIP / PASS / FAIL counts from the layer logs ------------
total_pass=0; total_fail=0; total_skip=0
for t in $LAYERS; do
  log="$RUN_DIR/$t.log"
  [ -f "$log" ] || continue
  p=$(grep -c '✓ PASS' "$log" 2>/dev/null || true);  p=${p:-0}
  fl=$(grep -c '✗ FAIL' "$log" 2>/dev/null || true); fl=${fl:-0}
  s=$(grep -c '‒ SKIP' "$log" 2>/dev/null || true);  s=${s:-0}
  total_pass=$((total_pass + p))
  total_fail=$((total_fail + fl))
  total_skip=$((total_skip + s))
done

# --- summary -------------------------------------------------------------
SUMMARY="$RUN_DIR/summary.md"
{
  echo "# CodeGraph verification suite — $TS"
  echo
  echo "- repo: \`$REPO_ROOT\`"
  echo "- mode: $( [ "$QUICK" -eq 1 ] && echo '--quick (layer 05 skipped)' || echo 'full' )"
  echo "- assertions: ${total_pass} pass · ${total_fail} fail · ${total_skip} skip (documented gaps)"
  echo
  echo "| layer | result |"
  echo "|-------|--------|"
  for r in "${RESULTS[@]}"; do
    echo "| ${r#* } | ${r%% *} |"
  done
  echo
  if [ "$total_skip" -gt 0 ]; then
    echo "## Documented gaps (SKIP)"
    echo
    echo "The following are honest gaps, never faked passes (§6.J / §6.L):"
    echo
    grep -h '‒ SKIP' "$RUN_DIR"/*.log 2>/dev/null | sed 's/‒ SKIP/-/' || true
    echo
  fi
  echo "## Verdict"
  echo
  if [ "$SUITE_FAIL" -eq 0 ]; then
    echo "**SUITE PASSED** — codegraph is installed, indexed, and verified."
    echo "CI green is necessary, never sufficient: review documented gaps above."
  else
    echo "**SUITE FAILED** — at least one layer FAILED. codegraph integration is"
    echo "not verified. See the failing layer log in this directory."
  fi
} > "$SUMMARY"

printf '%s\n' "================================================================"
printf '%sSUMMARY%s  %spass=%d%s  %sfail=%d%s  %sskip=%d%s\n' \
  "$B" "$N" "$G" "$total_pass" "$N" "$R" "$total_fail" "$N" "$Y" "$total_skip" "$N"
for r in "${RESULTS[@]}"; do
  case "$r" in
    PASS*)    printf '  %s✓%s %s\n' "$G" "$N" "$r" ;;
    FAIL*|MIS*) printf '  %s✗%s %s\n' "$R" "$N" "$r" ;;
  esac
done
printf 'summary written: %s\n' "$SUMMARY"
printf '%s\n' "================================================================"

if [ "$SUITE_FAIL" -eq 0 ]; then
  printf '%sCODEGRAPH VERIFICATION: PASSED%s\n' "$G" "$N"
  exit 0
else
  printf '%sCODEGRAPH VERIFICATION: FAILED%s\n' "$R" "$N"
  exit 1
fi
