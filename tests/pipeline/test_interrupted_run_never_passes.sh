#!/usr/bin/env bash
# Hermetic test: an INTERRUPTED pipeline run must never finalize to outcome PASS.
#
# FORENSIC ANCHOR (2026-08-23, found by the first genuine end-to-end run):
# a run was killed mid-build. Its process exit code was honest (1, "halted at:
# build"). Its report.json — the artifact SC-008 tells an auditor to read FIRST —
# said:
#
#     "outcome": "PASS"
#     "phases":  [{"name":"precondition","result":"PASS"}]
#     "build_artifacts": []      "evidence_summary": {"total": 0, ...}
#
# A run that never got past its first phase, produced zero artifacts and zero
# evidence, reported success.
#
# ROOT CAUSE: finalize_run_report's rule is
#     (.phases | length) > 0 and (.phases | all(.result == "PASS"))
# The `length > 0` guard closes the EMPTY case. It cannot close the TRUNCATED
# PREFIX case, because a truncated run's phases[] is a perfectly valid all-PASS
# list — it is just SHORTER than the run was supposed to be. Nothing in the
# report distinguished "ran everything and passed" from "stopped after phase 1".
#
# This is the same vacuous-pass class that phase-02-test.sh's PASS condition 4
# was added to close (a phase passing on zero Evidence Records), sitting one
# level up in the run report itself.
#
# THE FIX UNDER TEST: the orchestrator records which phase is in flight, and an
# interrupted run appends that phase as FAIL before finalizing. An interrupted
# phase did not pass, so saying so is not a fabrication — it is the truth the
# report was previously missing. The report schema is additionalProperties:false,
# so this is deliberately expressed in the EXISTING phases[] field rather than by
# widening the contract.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="${REPO_ROOT}/scripts/pipeline/lib/run-report.sh"
[[ -f "$LIB" ]] || { echo "FAIL: library not found: $LIB"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "FAIL: jq required"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT
cd "$WORKDIR"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$LIB"
SHA="0123456789abcdef0123456789abcdef01234567"

_outcome() { jq -r '.outcome' "$1"; }
_phases()  { jq -c '[.phases[] | {name,result}]' "$1"; }

echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): a run interrupted mid-phase must be FAIL"
echo "==============================================================="
echo "Reproduces the real 2026-08-23 run: precondition PASS, then killed"
echo "during build. Every existing PASS condition is satisfied vacuously."
echo ""

RUN_A="2026-08-23T10-15-30Z"
init_run_report "$RUN_A" "$SHA" >/dev/null
DIR_A=".lava-ci-evidence/pipeline-runs/${RUN_A}"
append_phase_result "$RUN_A" "precondition" "PASS" 0 "${DIR_A}/phase-00" >/dev/null

# The orchestrator marks a phase in flight before invoking its scripts.
if ! mark_phase_in_flight "$RUN_A" "build" >/dev/null 2>&1; then
  fail "mark_phase_in_flight is not available (the fix is not implemented)"
else
  pass "mark_phase_in_flight recorded the in-flight phase"
fi

# ... and here the run is killed. The close path runs:
append_interrupted_phase_if_any "$RUN_A" >/dev/null 2>&1 || true
recompute_evidence_summary "$RUN_A" >/dev/null 2>&1 || true
finalize_run_report "$RUN_A" >/dev/null 2>&1 || true

REPORT_A="${DIR_A}/report.json"
got_outcome="$(_outcome "$REPORT_A")"
if [[ "$got_outcome" == "FAIL" ]]; then
  pass "an interrupted run finalizes to FAIL (outcome == FAIL)"
else
  fail "outcome is '${got_outcome}', expected FAIL — a run killed during 'build', with zero artifacts and zero evidence, reported success. Phases: $(_phases "$REPORT_A")"
fi

if jq -e '[.phases[] | select(.name=="build" and .result=="FAIL")] | length == 1' "$REPORT_A" >/dev/null 2>&1; then
  pass "the interrupted phase is recorded as FAIL, naming which phase was in flight"
else
  fail "no build/FAIL entry; phases are $(_phases "$REPORT_A") — the report does not say which phase was interrupted"
fi

echo ""
echo "==============================================================="
echo "CASE 2: a COMPLETED run must still reach PASS (no over-correction)"
echo "==============================================================="

RUN_B="2026-08-23T11-00-00Z"
init_run_report "$RUN_B" "$SHA" >/dev/null
DIR_B=".lava-ci-evidence/pipeline-runs/${RUN_B}"
append_phase_result "$RUN_B" "precondition" "PASS" 0 "${DIR_B}/phase-00" >/dev/null
mark_phase_in_flight "$RUN_B" "build" >/dev/null 2>&1 || true
append_phase_result "$RUN_B" "build" "PASS" 5 "${DIR_B}/phase-01" >/dev/null
# The phase completed, so the orchestrator clears the marker.
clear_phase_in_flight "$RUN_B" >/dev/null 2>&1 || true

append_interrupted_phase_if_any "$RUN_B" >/dev/null 2>&1 || true
recompute_evidence_summary "$RUN_B" >/dev/null 2>&1 || true
finalize_run_report "$RUN_B" >/dev/null 2>&1 || true

REPORT_B="${DIR_B}/report.json"
got_b="$(_outcome "$REPORT_B")"
if [[ "$got_b" == "PASS" ]]; then
  pass "a run whose phases all completed still reaches PASS"
else
  fail "outcome is '${got_b}', expected PASS — the fix is failing runs that genuinely completed. Phases: $(_phases "$REPORT_B")"
fi
if jq -e '[.phases[] | select(.result=="FAIL")] | length == 0' "$REPORT_B" >/dev/null 2>&1; then
  pass "no phantom FAIL entry was appended to a completed run"
else
  fail "a completed run gained a FAIL entry: $(_phases "$REPORT_B")"
fi

echo ""
echo "==============================================================="
echo "CASE 3: the marker is per-run, never leaking between runs"
echo "==============================================================="

# This case needs a marker belonging to ANOTHER run to be PRESENT while this
# run closes. It used to lean on "RUN_A above was left interrupted", which was
# not true: CASE 1's append_interrupted_phase_if_any CONSUMED RUN_A's marker
# (removing it is how the operation is made idempotent) and CASE 2 cleared
# RUN_B's, so by the time this case ran there was no marker anywhere on disk
# and the assertion had nothing to discriminate against. Measured 2026-08-25:
# with _phase_in_flight_marker deliberately changed to a single RUN-SHARED
# path — a real cross-run leak — this case still reported PASS and the whole
# suite still exited 0. A check that passes having examined zero items is the
# vacuous-pass shape this very file exists to close.
#
# So: RUN_D stands in for a DIFFERENT run that is still in flight right now,
# and its marker is deliberately left un-consumed across RUN_C's close.
RUN_C="2026-08-23T12-00-00Z"
RUN_D="2026-08-23T13-00-00Z"
init_run_report "$RUN_C" "$SHA" >/dev/null
init_run_report "$RUN_D" "$SHA" >/dev/null
DIR_C=".lava-ci-evidence/pipeline-runs/${RUN_C}"
append_phase_result "$RUN_C" "precondition" "PASS" 0 "${DIR_C}/phase-00" >/dev/null

# mark_phase_in_flight prints the marker path it wrote — use that rather than
# reconstructing it, so a change to WHERE the marker lives is caught here too.
FOREIGN_MARKER="$(mark_phase_in_flight "$RUN_D" "build")"
if [[ -f "$FOREIGN_MARKER" ]]; then
  pass "another run's marker is genuinely present on disk while this run closes"
else
  fail "harness assumption broken: no foreign marker at '${FOREIGN_MARKER}' — the rest of this case would prove nothing"
fi

append_interrupted_phase_if_any "$RUN_C" >/dev/null 2>&1 || true
recompute_evidence_summary "$RUN_C" >/dev/null 2>&1 || true
finalize_run_report "$RUN_C" >/dev/null 2>&1 || true
if jq -e '[.phases[] | select(.result=="FAIL")] | length == 0' "${DIR_C}/report.json" >/dev/null 2>&1; then
  pass "a different run is unaffected by another run's in-flight marker"
else
  fail "run C inherited a FAIL from run D's marker: $(_phases "${DIR_C}/report.json")"
fi
if [[ -f "$FOREIGN_MARKER" ]]; then
  pass "closing run C left run D's marker untouched — run D is still in flight"
else
  fail "closing run C CONSUMED run D's marker at '${FOREIGN_MARKER}' — the marker is not per-run, and run D's eventual interruption can no longer be recorded"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"; exit 0
else
  echo "$FAILURES CHECK(S) FAILED"; exit 1
fi
