#!/usr/bin/env bash
# Hermetic regression suite for LVA-161 — scripts/pipeline/phase-02-test-challenge.sh
# must (1) forward an explicitly-sized --test-timeout to the sibling matrix
# script, and (2) report a KILLED/TIMED-OUT runner as UNCONFIRMED, never as a
# wall of per-class FAILs and never as a pass.
#
# No emulator, no container, no Gradle, no real Challenge is ever run. The
# sibling matrix scripts are replaced by stubs that record the arguments they
# received and write whatever attestation the case under test needs, so what is
# under test is the wrapper's own reasoning.
#
# FORENSIC ANCHOR (measured, run_id 2026-08-26T14-09-17Z):
#   phase-02-test-challenge.sh selected 73 Challenge classes (108 @Test methods)
#   in ONE emulator-matrix invocation. Neither it nor scripts/run-challenge-
#   matrix.sh passed --test-timeout, so cmd/emulator-matrix's 10-minute default
#   (main.go:123) applied. The attestation row read:
#       test_seconds=600.02   test_error="signal: killed"
#   Gradle's last progress line was "Tests 81/104 completed. (6 skipped)
#   (12 failed)" — it was killed BEFORE writing any JUnit XML, so the wrapper
#   parsed 0 files and marked all 73 classes FAIL. That is 74 reported broken
#   features that were never actually tested, while the 12 tests that had
#   genuinely failed were buried in the same undifferentiated wall.
#
#   The harm runs in BOTH directions and this suite guards both:
#     * CASE B — a killed runner must NOT be reported as per-class FAIL
#       (manufacturing defects), and must NOT exit 0 (hiding a dead gate).
#     * CASE C — a runner that RAN TO COMPLETION and still produced no matching
#       <testcase> must STILL be FAIL. Otherwise the CASE B fix becomes a
#       universal excuse and a real breakage gets dismissed as "just the
#       timeout".
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-challenge.sh"
MATRIX="${REPO_ROOT}/scripts/run-challenge-matrix.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
[[ -f "$MATRIX"  ]] || { echo "FAIL: script under test not found: $MATRIX";  exit 1; }
for tool in jq python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
EXAMINED=0
pass() { echo "PASS: $1"; EXAMINED=$((EXAMINED + 1)); }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); EXAMINED=$((EXAMINED + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

MARKER_KDOC='/** FALSIFIABILITY REHEARSAL: inverted the assertion; the test failed with "expected true". */'

# _new_fixture <name> <app-class-count> — repo tree with N app Challenge classes.
_new_fixture() {
  local name="$1" n="${2:-1}" f i
  f="${WORKDIR}/${name}"
  mkdir -p "${f}/scripts" \
           "${f}/app/src/androidTest/kotlin/lava/app/challenges" \
           "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges"
  for ((i = 1; i <= n; i++)); do
    printf 'package lava.app.challenges\n%s\nclass Challenge%02dFooTest\n' "$MARKER_KDOC" "$i" \
      > "${f}/app/src/androidTest/kotlin/lava/app/challenges/Challenge$(printf '%02d' "$i")FooTest.kt"
  done
  printf 'package lava.api.app.challenges\n%s\nclass Challenge02BarTest\n' "$MARKER_KDOC" \
    > "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges/Challenge02BarTest.kt"
  printf '%s' "$f"
}

# _stub_matrix <fixture> <attestation-json-template> [gradle-progress-line]
# The stub records every argument it was given to <evidence-dir>/../ARGS-<script>
# and writes the given attestation (plus an optional gradle.log for salvage).
_stub_matrix() {
  local f="$1" attest_json="$2" progress="${3:-}"
  local s
  for s in run-challenge-matrix.sh run-api-app-challenge-matrix.sh; do
    cat > "${f}/scripts/${s}" <<STUB
#!/usr/bin/env bash
ev=""
printf '%s\n' "\$@" > "${f}/ARGS-${s}.txt"
while [[ \$# -gt 0 ]]; do case "\$1" in --evidence-dir) ev="\$2"; shift 2;; *) shift;; esac; done
if [[ -n "\$ev" ]]; then
  mkdir -p "\$ev/CZ_API34_Phone"
  cat > "\$ev/real-device-verification.json" <<'ATT'
${attest_json}
ATT
  if [[ -n "${progress}" ]]; then
    printf '> Task :app:connectedDebugAndroidTest\n%s\n' "${progress}" > "\$ev/CZ_API34_Phone/gradle.log"
  fi
fi
exit 1
STUB
    chmod +x "${f}/scripts/${s}"
  done
}

_run() {
  local f="$1" pd="$2"; shift 2
  local out="${WORKDIR}/wrapper-output.log"
  env "$@" LAVA_PIPELINE_CHALLENGE_CONTAINER_RUNTIME=false \
    bash "$WRAPPER" "$f" "$pd" >"$out" 2>&1
  W_RC=$?
  W_OUT="$(cat "$out")"
}

# Reads every written Evidence Record's result field.
_results() { find "$1" -mindepth 2 -maxdepth 2 -name '*.json' -exec jq -r '.result // empty' {} \; 2>/dev/null; }

FULL_DIAG='"diag":{"target":"google_apis","sdk":34,"device":"CZ_API34_Phone","adb_devices_state":"device"}'

echo "==============================================================="
echo "CASE A (LOAD-BEARING): --test-timeout is forwarded, and it scales"
echo "==============================================================="
echo "Zero occurrences of --test-timeout in scripts/ was the root cause: the"
echo "submodule's 10-minute default silently applied to a 73-class sweep."
echo ""

ATT_OK="{\"gating\":true,\"rows\":[{\"avd\":\"CZ_API34_Phone\",\"api_level\":34,\"test_passed\":true,\"concurrent\":1,\"test_seconds\":12.0,${FULL_DIAG},\"failure_summaries\":[]}]}"

FA="$(_new_fixture timeout-forwarded 2)"
_stub_matrix "$FA" "$ATT_OK"
_run "$FA" "${WORKDIR}/pd-a"

ARGS_A="$(cat "${FA}/ARGS-run-challenge-matrix.sh.txt" 2>/dev/null || true)"
if grep -q -- '--test-timeout' <<< "$ARGS_A"; then
  pass "--test-timeout IS forwarded to the sibling matrix script"
else
  fail "--test-timeout was NOT forwarded. The runner falls back to cmd/emulator-matrix's 10m default, which is what killed the 73-class sweep at 600.02s. Args seen: ${ARGS_A}"
fi

TO_A="$(awk '/^--test-timeout$/{getline; print; exit}' <<< "$ARGS_A")"
TO_A_NUM="${TO_A%s}"
if [[ "$TO_A_NUM" =~ ^[0-9]+$ ]] && (( TO_A_NUM > 0 )); then
  pass "forwarded budget is a positive duration (${TO_A} for 2 classes)"
else
  fail "forwarded budget '${TO_A}' is not a positive duration"
fi

# The 600s figure is only meaningful at the REAL production selection size.
# The killed run selected 73 app Challenge classes; the budget derived for that
# many classes MUST exceed both the 600s that was proved insufficient and the
# ~770s the measured 81-tests-in-600.02s throughput extrapolates to for 104
# tests. Asserting this at production scale is what stops the number from
# being a guess.
FA73="$(_new_fixture timeout-production-scale 73)"
_stub_matrix "$FA73" "$ATT_OK"
_run "$FA73" "${WORKDIR}/pd-a73"
TO_73="$(awk '/^--test-timeout$/{getline; print; exit}' < "${FA73}/ARGS-run-challenge-matrix.sh.txt")"
TO_73_NUM="${TO_73%s}"
if [[ "$TO_73_NUM" =~ ^[0-9]+$ ]] && (( TO_73_NUM > 600 )) && (( TO_73_NUM >= 770 )); then
  pass "at the real 73-class scale the budget is ${TO_73} — above the measured-insufficient 600s and above the ~770s the measured throughput requires"
else
  fail "at the real 73-class scale the budget is '${TO_73}', which does not clear both the measured-insufficient 600s and the ~770s extrapolated requirement (81 tests in 600.02s => 7.41 s/test => 104 tests ~= 770s)"
fi

# scaling: 4 classes must buy strictly more budget than 2.
FA2="$(_new_fixture timeout-scales 4)"
_stub_matrix "$FA2" "$ATT_OK"
_run "$FA2" "${WORKDIR}/pd-a2"
TO_B="$(awk '/^--test-timeout$/{getline; print; exit}' < "${FA2}/ARGS-run-challenge-matrix.sh.txt")"
if [[ -n "${TO_B%s}" ]] && (( ${TO_B%s} > TO_A_NUM )); then
  pass "budget scales with the selection (2 classes -> ${TO_A}, 4 classes -> ${TO_B}) — it cannot silently rot as classes are added"
else
  fail "budget did not grow with the class count (2 -> ${TO_A}, 4 -> ${TO_B}); a fixed number is a defect waiting to recur"
fi

echo ""
echo "==============================================================="
echo "CASE B (LOAD-BEARING): a KILLED runner is UNCONFIRMED, not FAIL"
echo "==============================================================="

ATT_KILLED="{\"gating\":true,\"rows\":[{\"avd\":\"CZ_API34_Phone\",\"api_level\":34,\"test_passed\":false,\"concurrent\":1,\"test_seconds\":600.02,\"test_error\":\"signal: killed\",\"gradle_log_path\":\"CZ_API34_Phone/gradle.log\",${FULL_DIAG},\"failure_summaries\":[]}]}"

FB="$(_new_fixture killed-runner 3)"
_stub_matrix "$FB" "$ATT_KILLED" 'Tests 81/104 completed. (6 skipped) (12 failed)'
PDB="${WORKDIR}/pd-b"
_run "$FB" "$PDB"

RES_B="$(_results "$PDB")"
N_FAIL_B="$(grep -c '^FAIL$' <<< "$RES_B" || true)"
N_SKIP_B="$(grep -c '^SKIPPED$' <<< "$RES_B" || true)"

if [[ "$N_SKIP_B" -gt 0 && "$N_FAIL_B" -eq 0 ]]; then
  pass "killed runner -> ${N_SKIP_B} SKIPPED, 0 FAIL (no manufactured defects)"
else
  fail "killed runner -> ${N_FAIL_B} FAIL / ${N_SKIP_B} SKIPPED. A kill destroys every class's result at once; reporting them as FAIL invents breakages that were never observed."
fi

if grep -qi 'UNCONFIRMED' <<< "$(find "$PDB" -name '*.json' -exec cat {} \; 2>/dev/null)"; then
  pass "the Evidence Records say UNCONFIRMED in so many words"
else
  fail "no Evidence Record marks the outcome UNCONFIRMED; a future reader cannot tell a kill from a verdict"
fi

if grep -q '81/104' <<< "$W_OUT$(find "$PDB" -name '*.json' -exec cat {} \; 2>/dev/null)"; then
  pass "the salvaged Gradle progress line (81/104, 12 failed) survives into the evidence"
else
  fail "the runner's own progress line was not salvaged. Without it the 12 genuinely-failing tests vanish and the abort looks harmless — the 'dismiss a real breakage as just the timeout' direction of the harm."
fi

if [[ "$W_RC" -ne 0 ]]; then
  pass "killed runner -> wrapper exits non-zero (${W_RC}); a dead gate is not a green gate"
else
  fail "killed runner -> wrapper exited 0. SKIPPED alone does not block the phase, so a timed-out gate would masquerade as passing."
fi

echo ""
echo "==============================================================="
echo "CASE C (DISCRIMINATION): a COMPLETED runner with no matching XML"
echo "is still FAIL — the CASE B fix must not become a universal excuse"
echo "==============================================================="

ATT_COMPLETED="{\"gating\":true,\"rows\":[{\"avd\":\"CZ_API34_Phone\",\"api_level\":34,\"test_passed\":false,\"concurrent\":1,\"test_seconds\":42.5,\"test_error\":\"exit status 1\",${FULL_DIAG},\"failure_summaries\":[]}]}"

FC="$(_new_fixture completed-no-xml 3)"
_stub_matrix "$FC" "$ATT_COMPLETED"
PDC="${WORKDIR}/pd-c"
_run "$FC" "$PDC"

RES_C="$(_results "$PDC")"
N_FAIL_C="$(grep -c '^FAIL$' <<< "$RES_C" || true)"
if [[ "$N_FAIL_C" -gt 0 ]]; then
  pass "completed-but-empty run still yields ${N_FAIL_C} FAIL (real absence, not an abort artefact)"
else
  fail "a runner that ran to completion (test_seconds=42.5, 'exit status 1', no kill) produced no FAIL. The abort carve-out has swallowed genuine failures — exactly the 'dismiss a real breakage as just the timeout' harm."
fi
if [[ "$W_RC" -ne 0 ]]; then
  pass "completed-but-empty run -> wrapper exits non-zero (${W_RC})"
else
  fail "completed-but-empty run -> wrapper exited 0"
fi

echo ""
echo "==============================================================="
echo "EXAMINED: ${EXAMINED} assertion(s)"
if [[ "$EXAMINED" -eq 0 ]]; then
  echo "FAILED — zero assertions were examined. A suite that checked nothing proves nothing."
  exit 1
fi
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED (${EXAMINED} examined)"
  exit 0
fi
echo "${FAILURES} CHECK(S) FAILED (${EXAMINED} examined)"
exit 1
