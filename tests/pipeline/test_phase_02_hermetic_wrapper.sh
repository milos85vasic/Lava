#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test-hermetic.sh's
# ENUMERATION + SKIP honesty (audit of T025's deliverable, 2026-08-21).
#
# The wrapper under test is driven entirely through its documented
# `<repo-path> <phase-dir>` argument seam against a synthetic tests/ tree, so
# what is exercised is the wrapper's own enumeration + result + evidence
# logic, never this project's ~80 real suites.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-21 wrapper audit):
# The wrapper enumerates `tests/<subdir>/*.sh` and then excludes aggregators,
# helper libraries and a heavy fixture-copy suite. When that enumeration
# matches NOTHING that survives exclusion, the run loop never executes, so
# PASS_COUNT=0, FAIL_COUNT=0, REJECTED_COUNT=0 — and the wrapper exits 0.
# Observed verbatim against a synthetic tree whose only candidate was an
# excluded aggregator:
#
#   phase-02-test-hermetic: 0 suite(s) to run, 1 skipped
#     suites run:      0
#     PASS:            0
#     FAIL:            0
#   WRAPPER EXIT CODE = 0
#   --- Evidence Records written: 0
#
# Zero suites executed, zero Evidence Records, exit 0 — the canonical
# "no tests therefore no failures therefore PASS" bug, and the same defect
# class as phase-02-test.sh's own CASE 2 ("an empty test phase proves
# nothing", tests/pipeline/test_phase_02_aggregation.sh). A future exclusion
# rule that accidentally matches every suite, a renamed tests/ layout, or a
# `find` that silently returns nothing would all report a green hermetic
# category having proven nothing at all.
#
# WHY CASE 3 EXISTS: the wrapper's own header states skipped suites are
# "reported honestly as a skip, never silently omitted" — but the only
# reporting was a console line. The Evidence Record schema has SKIPPED as a
# first-class result precisely so an honest non-execution survives into the
# run report; a console line does not. Every skip must leave a real SKIPPED
# Evidence Record quoting its real reason.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-hermetic.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "FAIL: 'jq' required"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# _run_wrapper <fixture-repo> -> sets RC and OUT
_run_wrapper() {
  local repo="$1"
  local out="${WORKDIR}/out.log"
  set +e
  bash "$WRAPPER" "$repo" "${repo}/phase-02" >"$out" 2>&1
  RC=$?
  set -e
  OUT="$(cat "$out")"
}

_records() { find "${1}/phase-02" -name '*.json' -not -path '*/raw/*' 2>/dev/null | sort; }

echo "==============================================================="
echo "CASE 1: real suites run -> honest PASS/FAIL (anti-'fail everything')"
echo "==============================================================="

GOOD="${WORKDIR}/good"; mkdir -p "${GOOD}/tests/suite"
printf '#!/usr/bin/env bash\necho "ALL CHECKS PASSED"\nexit 0\n' > "${GOOD}/tests/suite/test_ok.sh"
_run_wrapper "$GOOD"
if [[ "$RC" -eq 0 ]]; then
  pass "one genuinely passing suite -> exit 0"
else
  fail "one genuinely passing suite -> exit ${RC}, expected 0; output: ${OUT}"
fi
n_good="$(_records "$GOOD" | wc -l | tr -d ' ')"
if [[ "$n_good" -eq 1 ]]; then
  pass "one passing suite -> exactly 1 Evidence Record"
else
  fail "one passing suite -> ${n_good} Evidence Records, expected 1"
fi

BAD="${WORKDIR}/bad"; mkdir -p "${BAD}/tests/suite"
printf '#!/usr/bin/env bash\necho "ALL CHECKS PASSED"\nexit 0\n' > "${BAD}/tests/suite/test_ok.sh"
printf '#!/usr/bin/env bash\necho "FAIL: assertion 3 of 9 failed"\nexit 1\n' > "${BAD}/tests/suite/test_bad.sh"
_run_wrapper "$BAD"
if [[ "$RC" -ne 0 ]]; then
  pass "a genuinely failing suite -> non-zero exit (${RC})"
else
  fail "a genuinely failing suite -> exit 0; output: ${OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): enumeration matched nothing runnable"
echo "==============================================================="

EMPTY="${WORKDIR}/empty"; mkdir -p "${EMPTY}/tests/suite"
# run_all.sh is on the wrapper's own exclusion list, so every candidate the
# enumeration finds is excluded and zero suites remain to execute.
printf '#!/usr/bin/env bash\necho aggregator\nexit 0\n' > "${EMPTY}/tests/suite/run_all.sh"
_run_wrapper "$EMPTY"

if grep -q "0 suite(s) to run" <<< "$OUT"; then
  pass "fixture sanity: the wrapper really did enumerate zero runnable suites"
else
  fail "fixture sanity: expected '0 suite(s) to run'; output: ${OUT}"
fi

if [[ "$RC" -ne 0 ]]; then
  pass "zero executed suites -> non-zero exit (${RC})"
else
  fail "zero executed suites -> exit 0. The hermetic category reported success having executed nothing at all; an empty test category proves nothing (same principle phase-02-test.sh enforces for the phase as a whole)."
fi

echo ""
echo "==============================================================="
echo "CASE 3: a skipped suite leaves a real SKIPPED Evidence Record"
echo "==============================================================="

SKIPD="${WORKDIR}/skipped"; mkdir -p "${SKIPD}/tests/suite"
printf '#!/usr/bin/env bash\necho "ALL CHECKS PASSED"\nexit 0\n' > "${SKIPD}/tests/suite/test_ok.sh"
printf '#!/usr/bin/env bash\necho aggregator\nexit 0\n' > "${SKIPD}/tests/suite/run_all.sh"
_run_wrapper "$SKIPD"

skip_record=""
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  if [[ "$(jq -r '.result' "$r" 2>/dev/null)" == "SKIPPED" ]]; then
    skip_record="$r"
  fi
done < <(_records "$SKIPD")

if [[ -n "$skip_record" ]]; then
  pass "the excluded suite produced a SKIPPED Evidence Record ($(basename "$skip_record"))"
  summary="$(jq -r '.assertion_summary' "$skip_record")"
  if grep -qi 'aggregator' <<< "$summary"; then
    pass "the SKIPPED record quotes the wrapper's own real skip reason"
  else
    fail "the SKIPPED record's assertion_summary does not name the real reason: ${summary}"
  fi
  if [[ "$(jq -r '.anti_bluff_status' "$skip_record")" == "validated" ]]; then
    pass "the SKIPPED record survives anti-bluff validation"
  else
    fail "the SKIPPED record was rejected: $(jq -r '.anti_bluff_status' "$skip_record")"
  fi
else
  fail "an excluded suite left NO Evidence Record at all — the skip exists only as a console line and never reaches the run report, though the schema has SKIPPED as a first-class result for exactly this."
fi

if [[ "$RC" -eq 0 ]]; then
  pass "one real suite ran + one honest skip -> exit 0 (a skip is not a failure)"
else
  fail "one real suite ran + one honest skip -> exit ${RC}, expected 0; output: ${OUT}"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
