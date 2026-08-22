#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/lib/run-report.sh's
# `recompute_evidence_summary` (the Pipeline Run Report's evidence_summary
# aggregator).
#
# WHY THIS TEST EXISTS (forensic anchor, 2026-08-21, T038/T057 wiring):
# data-model.md's Validation rule states that a Pipeline Run Report's
# `outcome` is "PASS" if and only if every phase is PASS *AND*
# `evidence_summary.rejected_by_anti_bluff == 0`. `finalize_run_report`
# implements that rule literally and correctly. But at the time this test
# was written, `evidence_summary` was initialized to all-zeros by
# `init_run_report` and then NEVER updated by anything: `phase-02-test.sh`
# tallies the same counters locally and prints them to its console SUMMARY,
# but does not write them back into report.json. The consequence is exactly
# the bluff class this project's constitution exists to evict: a run whose
# Evidence Records were REJECTED by anti-bluff validation could still
# finalize to `outcome: "PASS"`, because the counter the rule reads was
# permanently 0. The rule looked enforced and was not.
#
# So this suite's load-bearing case is CASE 3: a run with a genuinely
# REJECTED Evidence Record on disk MUST finalize to "FAIL". If that case
# ever passes while a REJECTED record sits in the run directory, the
# anti-bluff half of the outcome rule has silently become a no-op again.
#
# Design note — why a separate aggregator rather than folding the scan into
# `finalize_run_report`: the existing suite
# tests/pipeline/test_evidence_and_run_report.sh asserts that
# `finalize_run_report` leaves a caller-set `evidence_summary` UNCHANGED
# (its "evidence_summary.skipped survived finalize_run_report unchanged"
# case). That is a deliberate separation of concerns — finalize applies the
# rule, an aggregator supplies the inputs — and this test does not break it.
# What was missing was the aggregator, not a change to finalize.
#
# Everything here runs inside a mktemp -d working directory, because every
# run-report.sh function resolves its report path relative to the CURRENT
# WORKING DIRECTORY. This suite never touches this repository's real
# .lava-ci-evidence/ tree.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_REPORT_LIB="${REPO_ROOT}/scripts/pipeline/lib/run-report.sh"
EVIDENCE_LIB="${REPO_ROOT}/scripts/pipeline/lib/evidence.sh"

for lib in "$RUN_REPORT_LIB" "$EVIDENCE_LIB"; do
  if [[ ! -f "$lib" ]]; then
    echo "FAIL: library under test not found: $lib"
    exit 1
  fi
done
if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
  exit 1
fi

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$RUN_REPORT_LIB"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$EVIDENCE_LIB"

FAKE_SHA="0123456789abcdef0123456789abcdef01234567"

# _field <report-path> <jq-path> — read one field out of a report.json.
_field() { jq -r "$2" "$1"; }

# _seed_record <phase_dir> <test_id> <category> <result> — write one REAL
# Evidence Record via the production writer, with a real non-empty raw
# output file behind it (write_evidence_record requires the raw file to
# exist so it can normalize raw_output_ref relative to the record).
_seed_record() {
  local phase_dir="$1" test_id="$2" category="$3" result="$4"
  local raw_dir="${phase_dir}/${category}/raw"
  mkdir -p -- "$raw_dir"
  local raw_path="${raw_dir}/${test_id}.log"
  printf 'real captured output for %s\nresult=%s\n' "$test_id" "$result" > "$raw_path"
  write_evidence_record \
    "$phase_dir" \
    "$test_id" \
    "$category" \
    "bash -c 'echo real captured output for ${test_id}'" \
    "$result" \
    "expected 3 rows for ${test_id}, observed 3 rows with matching ids" \
    "$raw_path" >/dev/null
}

echo "==============================================================="
echo "CASE 1: aggregator tallies real Evidence Records off disk"
echo "==============================================================="

cd "$WORKDIR"
RUN_1="2026-08-21T10-00-00Z"
init_run_report "$RUN_1" "$FAKE_SHA" >/dev/null
RUN_DIR_1=".lava-ci-evidence/pipeline-runs/${RUN_1}"
PHASE_DIR_1="${RUN_DIR_1}/phase-02"

_seed_record "$PHASE_DIR_1" "lava.core.AlphaTest" "kotlin-unit" "PASS"
_seed_record "$PHASE_DIR_1" "lava.core.BetaTest" "kotlin-unit" "PASS"
_seed_record "$PHASE_DIR_1" "lava.core.GammaTest" "kotlin-unit" "FAIL"
_seed_record "$PHASE_DIR_1" "TestGoDelta" "go-unit-integration" "SKIPPED"

if ! recompute_evidence_summary "$RUN_1" >/dev/null 2>&1; then
  fail "recompute_evidence_summary exited non-zero on a well-formed run"
else
  pass "recompute_evidence_summary exited 0 on a well-formed run"
fi

REPORT_1="${RUN_DIR_1}/report.json"
for expectation in "total 4" "passed 2" "failed 1" "skipped 1" "rejected_by_anti_bluff 0"; do
  key="${expectation%% *}"; want="${expectation##* }"
  got="$(_field "$REPORT_1" ".evidence_summary.${key}")"
  if [[ "$got" == "$want" ]]; then
    pass "evidence_summary.${key} == ${want}"
  else
    fail "evidence_summary.${key} is '${got}', expected '${want}'"
  fi
done

echo ""
echo "==============================================================="
echo "CASE 2: aggregator scans EVERY phase directory, not just one"
echo "==============================================================="

RUN_2="2026-08-21T11-00-00Z"
init_run_report "$RUN_2" "$FAKE_SHA" >/dev/null
RUN_DIR_2=".lava-ci-evidence/pipeline-runs/${RUN_2}"

_seed_record "${RUN_DIR_2}/phase-02" "lava.core.OneTest" "kotlin-unit" "PASS"
_seed_record "${RUN_DIR_2}/phase-03" "systemd-install-and-health" "hermetic-script" "PASS"
_seed_record "${RUN_DIR_2}/phase-04" "live-health-endpoint" "hermetic-script" "PASS"

recompute_evidence_summary "$RUN_2" >/dev/null 2>&1 || true
REPORT_2="${RUN_DIR_2}/report.json"
got_total_2="$(_field "$REPORT_2" '.evidence_summary.total')"
if [[ "$got_total_2" == "3" ]]; then
  pass "records from phase-02, phase-03 and phase-04 all counted (total == 3)"
else
  fail "evidence_summary.total is '${got_total_2}', expected 3 — the aggregator is not scanning every phase directory"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): a REJECTED record must force outcome FAIL"
echo "==============================================================="
echo "This is the case that proves data-model.md's Validation rule is"
echo "genuinely enforced. Every phase below is PASS; the ONLY thing that"
echo "may legitimately fail this run is the rejected Evidence Record."
echo ""

RUN_3="2026-08-21T12-00-00Z"
init_run_report "$RUN_3" "$FAKE_SHA" >/dev/null
RUN_DIR_3=".lava-ci-evidence/pipeline-runs/${RUN_3}"
PHASE_DIR_3="${RUN_DIR_3}/phase-02"

_seed_record "$PHASE_DIR_3" "lava.core.HonestTest" "kotlin-unit" "PASS"
_seed_record "$PHASE_DIR_3" "lava.core.BluffingTest" "kotlin-unit" "PASS"

# Mark the second record REJECTED exactly the way anti-bluff-validate.sh
# does (it rewrites anti_bluff_status in place to "REJECTED: <reason>").
BLUFF_RECORD="${PHASE_DIR_3}/kotlin-unit/lava.core.BluffingTest.json"
if [[ ! -f "$BLUFF_RECORD" ]]; then
  fail "fixture setup error: expected seeded record at ${BLUFF_RECORD}"
else
  tmp_bluff="${BLUFF_RECORD}.tmp"
  jq '.anti_bluff_status = "REJECTED: assertion_summary is generic boilerplate"' \
    "$BLUFF_RECORD" > "$tmp_bluff" && mv -f "$tmp_bluff" "$BLUFF_RECORD"
fi

append_phase_result "$RUN_3" "precondition" "PASS" 1 "${RUN_DIR_3}/phase-00" >/dev/null
append_phase_result "$RUN_3" "test" "PASS" 42 "$PHASE_DIR_3" >/dev/null

recompute_evidence_summary "$RUN_3" >/dev/null 2>&1 || true
finalize_run_report "$RUN_3" >/dev/null

REPORT_3="${RUN_DIR_3}/report.json"
got_rejected_3="$(_field "$REPORT_3" '.evidence_summary.rejected_by_anti_bluff')"
if [[ "$got_rejected_3" == "1" ]]; then
  pass "evidence_summary.rejected_by_anti_bluff == 1 (the REJECTED record was actually counted)"
else
  fail "evidence_summary.rejected_by_anti_bluff is '${got_rejected_3}', expected 1 — a REJECTED record on disk was not counted"
fi

got_outcome_3="$(_field "$REPORT_3" '.outcome')"
if [[ "$got_outcome_3" == "FAIL" ]]; then
  pass "outcome == FAIL despite every phase being PASS (anti-bluff rule is genuinely load-bearing)"
else
  fail "outcome is '${got_outcome_3}', expected FAIL — a run containing a REJECTED Evidence Record reported success. This is the exact bluff data-model.md's Validation rule exists to prevent."
fi

echo ""
echo "==============================================================="
echo "CASE 4: an all-clean run still reaches outcome PASS"
echo "(guards against 'fix' that just hardcodes FAIL)"
echo "==============================================================="

RUN_4="2026-08-21T13-00-00Z"
init_run_report "$RUN_4" "$FAKE_SHA" >/dev/null
RUN_DIR_4=".lava-ci-evidence/pipeline-runs/${RUN_4}"
_seed_record "${RUN_DIR_4}/phase-02" "lava.core.CleanTest" "kotlin-unit" "PASS"
append_phase_result "$RUN_4" "precondition" "PASS" 1 "${RUN_DIR_4}/phase-00" >/dev/null
append_phase_result "$RUN_4" "test" "PASS" 5 "${RUN_DIR_4}/phase-02" >/dev/null
recompute_evidence_summary "$RUN_4" >/dev/null 2>&1 || true
finalize_run_report "$RUN_4" >/dev/null

got_outcome_4="$(_field "${RUN_DIR_4}/report.json" '.outcome')"
if [[ "$got_outcome_4" == "PASS" ]]; then
  pass "a genuinely clean run still reaches outcome PASS"
else
  fail "outcome is '${got_outcome_4}', expected PASS — the aggregator is failing clean runs"
fi

echo ""
echo "==============================================================="
echo "CASE 5: a run with zero Evidence Records yields all-zero counters"
echo "==============================================================="

RUN_5="2026-08-21T14-00-00Z"
init_run_report "$RUN_5" "$FAKE_SHA" >/dev/null
recompute_evidence_summary "$RUN_5" >/dev/null 2>&1 || true
got_total_5="$(_field ".lava-ci-evidence/pipeline-runs/${RUN_5}/report.json" '.evidence_summary.total')"
if [[ "$got_total_5" == "0" ]]; then
  pass "an empty run reports total == 0 (no phantom records invented)"
else
  fail "evidence_summary.total is '${got_total_5}' for a run with no records, expected 0"
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
