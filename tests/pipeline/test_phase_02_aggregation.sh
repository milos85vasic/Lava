#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test.sh's AGGREGATION and
# PASS/FAIL policy (T029's deliverable).
#
# This suite does not run any real test wrapper. Every wrapper path is
# overridden (via the PHASE02_*_WRAPPER env hooks phase-02-test.sh already
# documents) to point either at a fast stub or at a nonexistent path, so the
# thing under test is the DISPATCH + AGGREGATION LOGIC itself, in isolation
# from Gradle, emulators, podman and the constitution sweep.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-21):
# phase-02-test.sh's header states its own governing principle — "an empty
# test phase proves nothing" — and enforces it for the case where ZERO
# wrappers were dispatched. It did not enforce it for the case where wrappers
# WERE dispatched, all exited 0, and produced ZERO Evidence Records between
# them. That run reported:
#
#   phase-02-test: PASSED — all 1 dispatched wrapper(s) exited 0,
#   0 Evidence Records scanned, 0 FAIL, 0 REJECTED
#
# A test phase that proved nothing, announcing PASS. Worse, it propagates:
# the run report's `outcome` would also be PASS, because every phase is PASS
# and `rejected_by_anti_bluff` is 0 — there is nothing to reject when there
# is nothing at all. This is the same defect class as the never-populated
# evidence_summary counter (see tests/pipeline/test_run_report_evidence_summary.sh):
# a guard whose stated principle is right and whose implementation does not
# cover the case that matters.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE02="${REPO_ROOT}/scripts/pipeline/phase-02-test.sh"

if [[ ! -f "$PHASE02" ]]; then
  echo "FAIL: script under test not found: $PHASE02"
  exit 1
fi
for tool in jq git python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# A stub wrapper that exits 0 and writes NOTHING.
cat > "${WORKDIR}/stub-silent.sh" <<'STUB'
#!/usr/bin/env bash
echo "stub-silent: ran, produced no Evidence Records"
exit 0
STUB

# A stub wrapper that exits 0 and writes ONE real Evidence Record via the
# production writer (not a hand-rolled JSON blob).
cat > "${WORKDIR}/stub-honest.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
REPO_PATH="$1"; PHASE_DIR="$2"
source "${LAVA_EVIDENCE_LIB}"
raw_dir="${PHASE_DIR}/hermetic-script/raw"
mkdir -p -- "$raw_dir"
raw="${raw_dir}/stub.log"
printf 'real captured output from the stub suite\n3 assertions, 3 passed\n' > "$raw"
write_evidence_record "$PHASE_DIR" "stub.HonestSuite" "hermetic-script" \
  "bash stub-honest.sh" "PASS" \
  "stub suite reported 3 of 3 assertions passing, matching its captured output" \
  "$raw" >/dev/null
exit 0
STUB
chmod +x "${WORKDIR}/stub-silent.sh" "${WORKDIR}/stub-honest.sh"

# _run_phase02 <fixture-dir> <run_id> <hermetic-wrapper-path>
# Points every wrapper except 'hermetic' at a nonexistent path so they skip,
# and 'hermetic' at the given stub. Sets P2_RC and P2_OUT.
_run_phase02() {
  local dir="$1" run_id="$2" wrapper="$3"
  local out_file="${WORKDIR}/phase02-output.log"
  set +e
  (
    cd "$dir" && \
    LAVA_EVIDENCE_LIB="${REPO_ROOT}/scripts/pipeline/lib/evidence.sh" \
    PHASE02_GO_WRAPPER=/nonexistent/go.sh \
    PHASE02_KOTLIN_WRAPPER=/nonexistent/kotlin.sh \
    PHASE02_STRESS_CHAOS_WRAPPER=/nonexistent/stress.sh \
    PHASE02_CHALLENGE_WRAPPER=/nonexistent/challenge.sh \
    PHASE02_RELEASE_CANARY_WRAPPER=/nonexistent/canary.sh \
    PHASE02_GATE_SWEEP_WRAPPER=/nonexistent/sweep.sh \
    PHASE02_HERMETIC_WRAPPER="$wrapper" \
      bash "$PHASE02" "$run_id" "$dir"
  ) >"$out_file" 2>&1
  P2_RC=$?
  set -e
  P2_OUT="$(cat "$out_file")"
}

_new_run() {
  local name="$1" run_id="$2"
  local dir="${WORKDIR}/${name}"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${dir}/.gitignore"
  printf 'x\n' > "${dir}/f"
  git -C "$dir" add -A
  git -C "$dir" commit -qm init
  ( cd "$dir" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" && \
    init_run_report "$run_id" "$(git -C "$dir" rev-parse HEAD)" >/dev/null )
  printf '%s' "$dir"
}

echo "==============================================================="
echo "CASE 1: a wrapper that produces real evidence -> PASS"
echo "(guards against a 'fix' that just makes everything fail)"
echo "==============================================================="

RUN_A="2026-08-21T21-00-00Z"
DIR_A="$(_new_run honest "$RUN_A")"
_run_phase02 "$DIR_A" "$RUN_A" "${WORKDIR}/stub-honest.sh"

if [[ "$P2_RC" -eq 0 ]]; then
  pass "honest wrapper with 1 real Evidence Record -> exit 0"
else
  fail "honest wrapper -> exit ${P2_RC}, expected 0; output: ${P2_OUT}"
fi
REPORT_A="${DIR_A}/.lava-ci-evidence/pipeline-runs/${RUN_A}/report.json"
res_a="$(jq -r '.phases[] | select(.name=="test") | .result' "$REPORT_A" 2>/dev/null)"
if [[ "$res_a" == "PASS" ]]; then
  pass "honest wrapper: report.json records test phase PASS"
else
  fail "honest wrapper: test phase result is '${res_a}', expected PASS"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): wrappers ran, produced ZERO evidence"
echo "==============================================================="
echo "phase-02-test.sh's own header states the principle: 'an empty test"
echo "phase proves nothing'. A dispatched wrapper that exits 0 having"
echo "written no Evidence Record leaves the phase with nothing to stand on."
echo ""

RUN_B="2026-08-21T22-00-00Z"
DIR_B="$(_new_run silent "$RUN_B")"
_run_phase02 "$DIR_B" "$RUN_B" "${WORKDIR}/stub-silent.sh"

if grep -q "dispatching 'hermetic'" <<< "$P2_OUT"; then
  pass "fixture sanity: the silent wrapper really was dispatched"
else
  fail "fixture sanity: the silent wrapper was never dispatched, so this case proves nothing; output: ${P2_OUT}"
fi

if [[ "$P2_RC" -ne 0 ]]; then
  pass "zero Evidence Records -> non-zero exit (${P2_RC})"
else
  fail "zero Evidence Records -> exit 0. A test phase that scanned no evidence at all reported success. phase-02-test.sh's own stated principle is that an empty test phase proves nothing."
fi

REPORT_B="${DIR_B}/.lava-ci-evidence/pipeline-runs/${RUN_B}/report.json"
res_b="$(jq -r '.phases[] | select(.name=="test") | .result' "$REPORT_B" 2>/dev/null)"
if [[ "$res_b" == "FAIL" ]]; then
  pass "zero Evidence Records: report.json records test phase FAIL"
else
  fail "zero Evidence Records: test phase result is '${res_b}', expected FAIL — the run report would carry this forward as a passing phase"
fi

echo ""
echo "==============================================================="
echo "CASE 3: no wrapper dispatched at all -> FAIL (pre-existing guard)"
echo "==============================================================="

RUN_C="2026-08-21T23-00-00Z"
DIR_C="$(_new_run nowrappers "$RUN_C")"
_run_phase02 "$DIR_C" "$RUN_C" "/nonexistent/hermetic.sh"
if [[ "$P2_RC" -ne 0 ]] && grep -q "zero wrappers were dispatched" <<< "$P2_OUT"; then
  pass "zero wrappers dispatched -> non-zero exit with an explicit reason"
else
  fail "zero wrappers dispatched -> exit ${P2_RC} without the expected refusal; output: ${P2_OUT}"
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
