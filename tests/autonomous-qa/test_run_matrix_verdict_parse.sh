#!/usr/bin/env bash
# tests/autonomous-qa/test_run_matrix_verdict_parse.sh
# ---------------------------------------------------------------------------
# Hermetic, fixture-driven test for the run-matrix summary aggregation helpers
# (scripts/autonomous-qa/lib-summary.sh): qa_parse_field + qa_classify.
#
# Anti-bluff: the primary assertions are on user-visible OUTPUT — the parsed
# verdict/counter values and the aggregated PASS/SKIP/FAIL totals that land in
# summary.md. Fixtures mirror the EXACT byte shape run-iteration.sh emits
# (multi-key single line "tests": N, "failures": N, ... and "verdict": "X").
#
# Regression target: the historical bug `grep -oE '[A-Z]+$'` mislabelled every
# row as FAIL because the grep -o match ends in a double-quote. With that bug
# present, the PASS and SKIP fixtures below classify as FAIL and the totals
# assertion (PASS=1 SKIP=1 FAIL=2) fails loudly.
#
# Falsifiability rehearsal (manual, per §6.A/§6.N):
#   In lib-summary.sh change the verdict pattern back to:
#       grep -oE "\"$key\": \"[A-Z]+\"" | grep -oE '[A-Z]+$'
#   re-run this test -> it MUST fail with a clear assertion message; then revert.
# ---------------------------------------------------------------------------
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
LIB="$REPO_ROOT/scripts/autonomous-qa/lib-summary.sh"

# shellcheck source=/dev/null
source "$LIB"

FIX="$(mktemp -d)"
trap 'rm -rf "$FIX"' EXIT

# ----- Fixtures: byte-faithful to run-iteration.sh's verdict.json --------------
cat > "$FIX/pass.json" <<'JSON'
{
  "backend": "goapi",
  "providers": "rutracker",
  "query": "1080p",
  "serial": "127.0.0.1:51611",
  "gradle_rc": 0,
  "tests": 3, "failures": 0, "errors": 0, "skipped": 0,
  "verdict": "PASS",
  "note": "all assertions green",
  "junit_xml": "",
  "raw_dir": ""
}
JSON

cat > "$FIX/skip.json" <<'JSON'
{
  "backend": "goapi",
  "providers": "kinozal",
  "query": "mp3",
  "serial": "127.0.0.1:51611",
  "gradle_rc": 0,
  "tests": 2, "failures": 0, "errors": 0, "skipped": 2,
  "verdict": "SKIP",
  "note": "no credentials configured",
  "junit_xml": "",
  "raw_dir": ""
}
JSON

cat > "$FIX/fail.json" <<'JSON'
{
  "backend": "goapi",
  "providers": "rutor",
  "query": "1080p",
  "serial": "127.0.0.1:51611",
  "gradle_rc": 1,
  "tests": 5, "failures": 2, "errors": 1, "skipped": 0,
  "verdict": "FAIL",
  "note": "download marker absent",
  "junit_xml": "",
  "raw_dir": ""
}
JSON

# Deliberately DO NOT create "$FIX/missing.json" — exercises the missing-file path.
MISSING="$FIX/missing.json"

FAILS=0
check() { # check <desc> <expected> <actual>
  local desc="$1" exp="$2" act="$3"
  if [[ "$act" == "$exp" ]]; then
    printf 'PASS  %-44s exp=%-6s got=%s\n' "$desc" "$exp" "$act"
  else
    printf 'FAIL  %-44s exp=%-6s got=%s\n' "$desc" "$exp" "$act"
    FAILS=$((FAILS+1))
  fi
}

echo "== qa_parse_field: verdict =="
check "pass verdict"    PASS "$(qa_parse_field "$FIX/pass.json" verdict || echo FAIL)"
check "skip verdict"    SKIP "$(qa_parse_field "$FIX/skip.json" verdict || echo FAIL)"
check "fail verdict"    FAIL "$(qa_parse_field "$FIX/fail.json" verdict || echo FAIL)"
check "missing verdict" FAIL "$(qa_parse_field "$MISSING"       verdict || echo FAIL)"

echo "== qa_parse_field: numeric counters (FAIL fixture) =="
check "fail tests"      5 "$(qa_parse_field "$FIX/fail.json" tests    || echo 0)"
check "fail failures"   2 "$(qa_parse_field "$FIX/fail.json" failures || echo 0)"
check "fail errors"     1 "$(qa_parse_field "$FIX/fail.json" errors   || echo 0)"
check "fail skipped"    0 "$(qa_parse_field "$FIX/fail.json" skipped  || echo 0)"

echo "== qa_parse_field: numeric counters (SKIP fixture) =="
check "skip tests"      2 "$(qa_parse_field "$FIX/skip.json" tests    || echo 0)"
check "skip skipped"    2 "$(qa_parse_field "$FIX/skip.json" skipped  || echo 0)"

echo "== qa_parse_field: missing file -> numeric fallback =="
check "missing tests"   0 "$(qa_parse_field "$MISSING" tests || echo 0)"

echo "== qa_classify =="
check "classify PASS"    PASS "$(qa_classify PASS)"
check "classify SKIP"    SKIP "$(qa_classify SKIP)"
check "classify FAIL"    FAIL "$(qa_classify FAIL)"
check "classify unknown" FAIL "$(qa_classify WAT)"
check "classify empty"   FAIL "$(qa_classify "")"

echo "== aggregation totals (the summary.md bug) =="
# Replays the run-matrix loop's parse->classify->count over all 4 iterations
# (3 present fixtures + 1 missing). Truth: PASS=1 SKIP=1 FAIL=2.
pass=0; skip=0; fail=0
for jf in "$FIX/pass.json" "$FIX/skip.json" "$FIX/fail.json" "$MISSING"; do
  v="$(qa_parse_field "$jf" verdict || echo FAIL)"
  case "$(qa_classify "$v")" in
    PASS) pass=$((pass+1)) ;;
    SKIP) skip=$((skip+1)) ;;
    *)    fail=$((fail+1)) ;;
  esac
done
check "totals PASS" 1 "$pass"
check "totals SKIP" 1 "$skip"
check "totals FAIL" 2 "$fail"

echo "------------------------------------------------------------"
if [[ "$FAILS" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "FAILED CHECKS: $FAILS"
  exit 1
fi
