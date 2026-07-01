#!/usr/bin/env bash
# scripts/autonomous-qa/aggregate-evidence.sh
# ---------------------------------------------------------------------------
# Roll up the autonomous-QA per-iteration verdicts (written by run-iteration.sh)
# into:
#   1. A cycle-level CYCLE-SUMMARY.md (both backends, per-backend + overall
#      totals) under .lava-ci-evidence/autonomous-qa/<date>/.
#   2. The §6.AK distribute-gate artifacts that scripts/check-cycle-coverage.sh
#      consumes:
#        - cycle-coverage-map-<VER>.yaml   (top-level `version:` + `fixes:` list)
#        - <VER>-test-evidence.json        (version/channel/commit_sha/timestamp
#                                           + test_results[])
#      written to .lava-ci-evidence/distribute-changelog/firebase-app-distribution/
#      (the same --evidence-dir firebase-distribute.sh pins for the client app).
#
# ANTI-BLUFF (§6.J / §6.AK): every number is derived from the actual
# verdict.json files on disk. A PASS/total is NEVER emitted that is not present
# in the data. A missing or corrupt verdict.json is counted as FAIL and noted —
# never silently skipped. Only iterations whose verdict is PASS become asserted
# claims in the cycle-coverage-map (a claim asserts the feature works; asserting
# a FAILed iteration would be the exact bluff §6.AK forbids).
#
# Usage:
#   aggregate-evidence.sh --date <YYYY-MM-DD> --version <v> \
#                         --channel <debug|release> --timestamp <ISO8601> \
#                         [--evidence-dir <dir>]
#
# Plan: docs/superpowers/plans/2026-06-29-autonomous-qa-backend-provider-matrix.md
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Covering device Challenge for the whole autonomous-QA matrix (parameterized).
CHALLENGE_FQN="lava.app.challenges.Challenge70AutonomousQaProviderMatrixTest"
# §6.AK device identity recorded per test_results row.
DEVICE="CZ_API34_Phone-containerized"
# Backends to walk, in fixed order.
BACKENDS=("goapi" "apiapp")

usage() {
  echo "usage: $0 --date <YYYY-MM-DD> --version <v> --channel <debug|release> --timestamp <ISO8601> [--evidence-dir <dir>]" >&2
}

DATE=""; VER=""; CHAN=""; TS=""; EDIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date)          DATE="$2"; shift 2;;
    --version)       VER="$2"; shift 2;;
    --channel)       CHAN="$2"; shift 2;;
    --timestamp)     TS="$2"; shift 2;;
    --evidence-dir)  EDIR="$2"; shift 2;;
    *) usage; exit 2;;
  esac
done
[[ -z "$DATE" || -z "$VER" || -z "$CHAN" || -z "$TS" ]] && { usage; exit 2; }
case "$CHAN" in debug|release) ;; *) echo "FATAL: --channel must be debug|release" >&2; exit 2;; esac

# §6.AK evidence dir: the client distribute channel firebase-distribute.sh pins
# check-cycle-coverage.sh's --evidence-dir to. Both §6.AA stages (debug+release)
# share this directory; --evidence-dir overrides it for tests.
[[ -z "$EDIR" ]] && EDIR="$REPO_ROOT/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"

DATE_DIR="$REPO_ROOT/.lava-ci-evidence/autonomous-qa/$DATE"
SUMMARY="$DATE_DIR/CYCLE-SUMMARY.md"
MAP="$EDIR/cycle-coverage-map-$VER.yaml"
EVI="$EDIR/$VER-test-evidence.json"

SHA="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"

# --- JSON/XML extraction helpers (jq-free; tolerant of missing fields) -------
json_str() { # <file> <key> -> string value (empty if absent)
  local line
  line="$(grep -oE "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$1" 2>/dev/null | head -1)" || line=""
  [[ -z "$line" ]] && { printf ''; return 0; }
  printf '%s' "$line" | sed -E 's/^[^:]*:[[:space:]]*"//; s/"$//'
}
json_num() { # <file> <key> -> integer value (empty if absent)
  local m
  m="$(grep -oE "\"$2\"[[:space:]]*:[[:space:]]*-?[0-9]+" "$1" 2>/dev/null | head -1)" || m=""
  [[ -z "$m" ]] && { printf ''; return 0; }
  printf '%s' "$m" | grep -oE -- '-?[0-9]+' | tail -1
}
junit_time() { # <junit.xml> -> seconds (or the literal "null")
  local t=""
  if [[ -f "$1" ]]; then
    # Isolate the time="..." token first; matching [0-9.]+ over the whole tag
    # would catch the dots in classname="lava.app.challenges..." instead.
    t="$(grep -oE '<testcase[^>]*time="[0-9.]+"' "$1" 2>/dev/null | head -1 | grep -oE 'time="[0-9.]+"' | head -1 | grep -oE '[0-9.]+' | head -1)" || t=""
    [[ -z "$t" ]] && { t="$(grep -oE '<testsuite[^>]*time="[0-9.]+"' "$1" 2>/dev/null | head -1 | grep -oE 'time="[0-9.]+"' | head -1 | grep -oE '[0-9.]+' | head -1)" || t=""; }
  fi
  [[ -z "$t" ]] && printf 'null' || printf '%s' "$t"
}
json_esc() { printf '%s' "$1" | sed -E 's/\\/\\\\/g; s/"/\\"/g'; }

# --- Walk both backends, collecting rows / claims / evidence in ONE pass ------
declare -a TABLE_ROWS=()      # CYCLE-SUMMARY main table
declare -a PB_ROWS=()         # per-backend totals table
declare -a CLAIM_BLOCKS=()    # cycle-coverage-map fixes (PASS-only)
declare -a EVI_OBJS=()        # test-evidence test_results[]

ov_iter=0; ov_pass=0; ov_skip=0; ov_fail=0
ov_t=0; ov_f=0; ov_e=0; ov_s=0

for backend in "${BACKENDS[@]}"; do
  bdir="$DATE_DIR/$backend"
  b_iter=0; b_pass=0; b_skip=0; b_fail=0
  b_t=0; b_f=0; b_e=0; b_s=0

  if [[ -d "$bdir" ]]; then
    for idir in "$bdir"/*/; do
      [[ -d "$idir" ]] || continue
      vfile="$idir/verdict.json"
      jfile="$idir/junit.xml"
      slug="$(basename "$idir")"
      note=""
      verdict="FAIL"; providers="$slug"; query="?"
      t=0; f=0; e=0; s=0; dur="null"

      if [[ ! -f "$vfile" ]]; then
        note="verdict.json MISSING — counted FAIL"
      else
        verdict="$(json_str "$vfile" verdict)"
        providers="$(json_str "$vfile" providers)"
        query="$(json_str "$vfile" query)"
        t="$(json_num "$vfile" tests)"; f="$(json_num "$vfile" failures)"
        e="$(json_num "$vfile" errors)"; s="$(json_num "$vfile" skipped)"
        if [[ -z "$verdict" ]]; then
          note="verdict.json CORRUPT (no verdict field) — counted FAIL"
          verdict="FAIL"
        fi
        [[ -z "$providers" ]] && providers="$slug"
        [[ -z "$query" ]] && query="?"
        [[ "$verdict" =~ ^(PASS|FAIL|SKIP)$ ]] || { note="verdict.json CORRUPT (verdict='$verdict') — counted FAIL"; verdict="FAIL"; }
        # Only trust numeric fields; default unparseable ones to 0 (never invent).
        [[ "$t" =~ ^[0-9]+$ ]] || t=0
        [[ "$f" =~ ^[0-9]+$ ]] || f=0
        [[ "$e" =~ ^[0-9]+$ ]] || e=0
        [[ "$s" =~ ^[0-9]+$ ]] || s=0
        dur="$(junit_time "$jfile")"
      fi

      # Accumulate (assignment form — survives `set -e`).
      b_iter=$((b_iter+1)); b_t=$((b_t+t)); b_f=$((b_f+f)); b_e=$((b_e+e)); b_s=$((b_s+s))
      case "$verdict" in
        PASS) b_pass=$((b_pass+1));;
        SKIP) b_skip=$((b_skip+1));;
        *)    b_fail=$((b_fail+1));;
      esac

      TABLE_ROWS+=("| $backend | $providers | $query | $verdict | $t | $f | $e | $s | $note |")

      # test_results row (ALL iterations — full honest device-gate record).
      local_chal="$(json_esc "$CHALLENGE_FQN [$backend/$providers/$query]")"
      EVI_OBJS+=("$(cat <<OBJ
    {
      "challenge": "$local_chal",
      "status": "$verdict",
      "device": "$DEVICE",
      "duration_seconds": $dur
    }
OBJ
)")

      # cycle-coverage-map claim — PASS-only (anti-bluff: a claim asserts it works).
      if [[ "$verdict" == "PASS" ]]; then
        ctext="$(json_esc "$backend/$providers — search '$query' returns results, opens details, obtains download")"
        CLAIM_BLOCKS+=("  - fix: \"$ctext\"
    covering_challenges: [\"$CHALLENGE_FQN\"]")
      fi
    done
  fi

  PB_ROWS+=("| $backend | $b_iter | $b_pass | $b_skip | $b_fail | $b_t | $b_f | $b_e | $b_s |")
  ov_iter=$((ov_iter+b_iter)); ov_pass=$((ov_pass+b_pass)); ov_skip=$((ov_skip+b_skip)); ov_fail=$((ov_fail+b_fail))
  ov_t=$((ov_t+b_t)); ov_f=$((ov_f+b_f)); ov_e=$((ov_e+b_e)); ov_s=$((ov_s+b_s))
done

[[ "$ov_iter" -eq 0 ]] && echo "WARN: no iterations found under $DATE_DIR" >&2

# --- 1) CYCLE-SUMMARY.md -----------------------------------------------------
mkdir -p "$DATE_DIR"
{
  echo "# Autonomous-QA Cycle Summary — $VER ($CHAN) — $DATE"
  echo ""
  echo "Generated by scripts/autonomous-qa/aggregate-evidence.sh at $TS."
  echo "Commit: $SHA"
  echo ""
  echo "| backend | providers | query | verdict | tests | fail | err | skip | note |"
  echo "|---|---|---|---|---|---|---|---|---|"
  if [[ ${#TABLE_ROWS[@]} -gt 0 ]]; then printf '%s\n' "${TABLE_ROWS[@]}"; fi
  echo ""
  echo "## Per-backend totals"
  echo ""
  echo "| backend | iterations | PASS | SKIP | FAIL | tests | fail | err | skip |"
  echo "|---|---|---|---|---|---|---|---|---|"
  printf '%s\n' "${PB_ROWS[@]}"
  echo ""
  echo "## Overall totals"
  echo ""
  echo "| iterations | PASS | SKIP | FAIL | tests | fail | err | skip |"
  echo "|---|---|---|---|---|---|---|---|"
  echo "| $ov_iter | $ov_pass | $ov_skip | $ov_fail | $ov_t | $ov_f | $ov_e | $ov_s |"
} > "$SUMMARY"

# --- 2) cycle-coverage-map-<VER>.yaml ---------------------------------------
mkdir -p "$EDIR"
{
  echo "# §6.AK cycle-coverage-map for autonomous-QA cycle $VER (channel: $CHAN)"
  echo "# Generated by scripts/autonomous-qa/aggregate-evidence.sh on $TS."
  echo "# Each claim below is backed by a PASS verdict in $VER-test-evidence.json"
  echo "# and maps to the covering device Challenge executed on the §6.Z gate."
  echo "version: \"$VER\""
  echo "fixes:"
  if [[ ${#CLAIM_BLOCKS[@]} -gt 0 ]]; then
    printf '%s\n' "${CLAIM_BLOCKS[@]}"
  else
    echo "  # (no PASS verdicts this cycle — zero claims asserted, per anti-bluff §6.AK)"
  fi
} > "$MAP"

# --- 3) <VER>-test-evidence.json --------------------------------------------
{
  echo "{"
  echo "  \"version\": \"$VER\","
  echo "  \"channel\": \"$CHAN\","
  echo "  \"commit_sha\": \"$SHA\","
  echo "  \"timestamp\": \"$TS\","
  if [[ ${#EVI_OBJS[@]} -gt 0 ]]; then
    echo "  \"test_results\": ["
    for i in "${!EVI_OBJS[@]}"; do
      printf '%s' "${EVI_OBJS[$i]}"
      [[ "$i" -lt $((${#EVI_OBJS[@]}-1)) ]] && echo "," || echo ""
    done
    echo "  ]"
  else
    echo "  \"test_results\": []"
  fi
  echo "}"
} > "$EVI"

echo "[aggregate] CYCLE-SUMMARY : $SUMMARY" >&2
echo "[aggregate] coverage-map  : $MAP (${#CLAIM_BLOCKS[@]} PASS claim(s))" >&2
echo "[aggregate] test-evidence : $EVI (${#EVI_OBJS[@]} iteration row(s))" >&2
echo "[aggregate] totals        : iter=$ov_iter PASS=$ov_pass SKIP=$ov_skip FAIL=$ov_fail" >&2
