#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test-challenge.sh's
# DISPATCH + OUTCOME-CLASSIFICATION logic.
#
# No emulator, no container, no Gradle, no real Challenge test is ever run.
# Every seam this suite drives is one the script's own header already
# documents: the [repo-path] + [phase-dir] positionals, the
# LAVA_PIPELINE_CHALLENGE_{APP,API_APP}_TEST_CLASSES selection overrides, and
# LAVA_PIPELINE_CHALLENGE_CONTAINER_RUNTIME. The sibling matrix scripts are
# replaced by stubs, so what is under test is the wrapper's own reasoning
# about what those scripts did.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-22):
# process_module() returns 0 early when a module has zero selected classes.
# With BOTH modules at zero — which the documented selection overrides can
# produce, e.g. after a class rename leaves an operator's FQCN list matching
# nothing — the wrapper never invoked either sibling script, never wrote a
# single Evidence Record, and still printed:
#
#   phase-02-test-challenge: PASSED (SKIPPED counts as a legitimate, honestly-
#   reported non-failure outcome per the schema's own design intent)
#
# exiting 0. That is the same defect tests/pipeline/test_phase_02_aggregation.sh
# CASE 2 already fixed one level up in phase-02-test.sh ("an empty test phase
# proves nothing"), reappearing one level down. The aggregate guard does not
# rescue it: phase-02-test.sh only fails when the run has zero records IN
# TOTAL, so any other wrapper producing records lets this one contribute
# nothing while the phase still reports PASS.
#
# WHY CASE 3 EXISTS (forensic anchor, 2026-08-22):
# `local rc=$?` captured the sibling matrix script's real exit code, printed
# it three times, and never compared it. The mode decision was made purely on
# "did real-device-verification.json appear". So a matrix script that exited
# 0 — reporting SUCCESS — while writing no attestation was classified
# BLOCKED, and every class got a SKIPPED record asserting:
#
#   "BLOCKED (real, specific precondition gap -- not a feature defect)"
#
# a cause the wrapper cannot possibly know, contradicted by the tool's own
# success exit code. SKIPPED does not block the phase, so the wrapper exited
# 0. This is the identical shape to the already-fixed release-canary defect
# where undocumented exit codes were laundered into a SKIPPED that asserted a
# cause it could not know. The wrapper's own header documents exactly three
# exit codes (0 ran, 1 real-failure-or-image-preflight, 2 host-gap); only 1
# and 2 legitimately mean "blocked".
#
# WHY CASE 4 EXISTS (forensic anchor, 2026-08-22):
# discover_classes() drops any Challenge*Test.kt whose `package` line it
# cannot grep, and reported only the surviving count — "discovered 1 real
# Challenge class(es)" with three files on disk. A refactor that breaks the
# package-line match on N files silently removes N Challenge classes from the
# gate's inventory with no signal at all. Reporting 1/1 when 3 were there is
# the partial-result dishonesty this pipeline's other wrappers avoid.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-challenge.sh"

if [[ ! -f "$WRAPPER" ]]; then
  echo "FAIL: script under test not found: $WRAPPER"
  exit 1
fi
for tool in jq python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

MARKER_KDOC='/** FALSIFIABILITY REHEARSAL: inverted the assertion; the test failed with "expected true". */'

# _new_fixture <name> — a repo tree with both sibling matrix scripts present
# (as stubs) and one real-shaped Challenge class per module. Prints its path.
_new_fixture() {
  local name="$1"
  local f="${WORKDIR}/${name}"
  mkdir -p "${f}/scripts" \
           "${f}/app/src/androidTest/kotlin/lava/app/challenges" \
           "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges"
  printf 'package lava.app.challenges\n%s\nclass Challenge01FooTest\n' "$MARKER_KDOC" \
    > "${f}/app/src/androidTest/kotlin/lava/app/challenges/Challenge01FooTest.kt"
  printf 'package lava.api.app.challenges\n%s\nclass Challenge02BarTest\n' "$MARKER_KDOC" \
    > "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges/Challenge02BarTest.kt"
  printf '%s' "$f"
}

# _stub_matrix <fixture> <exit-code> <attestation:yes|no>
# Installs BOTH sibling matrix scripts as a stub with the given behaviour.
_stub_matrix() {
  local f="$1" rc="$2" attest="$3"
  local s
  for s in run-challenge-matrix.sh run-api-app-challenge-matrix.sh; do
    cat > "${f}/scripts/${s}" <<STUB
#!/usr/bin/env bash
ev=""
while [[ \$# -gt 0 ]]; do case "\$1" in --evidence-dir) ev="\$2"; shift 2;; *) shift;; esac; done
echo "stub matrix (${s}): invoked"
if [[ "${attest}" == "yes" && -n "\$ev" ]]; then
  mkdir -p "\$ev"
  printf '{"gating":true,"rows":[{"avd":"stub","api_level":34,"test_passed":true}]}\n' \
    > "\$ev/real-device-verification.json"
fi
exit ${rc}
STUB
    chmod +x "${f}/scripts/${s}"
  done
}

# _run <fixture> <phase-dir> [env assignments...] — sets W_RC and W_OUT.
_run() {
  local f="$1" pd="$2"; shift 2
  local out="${WORKDIR}/wrapper-output.log"
  set +e
  env "$@" LAVA_PIPELINE_CHALLENGE_CONTAINER_RUNTIME=false \
    bash "$WRAPPER" "$f" "$pd" >"$out" 2>&1
  W_RC=$?
  set -e
  W_OUT="$(cat "$out")"
}

_record_count() { find "$1" -mindepth 2 -maxdepth 2 -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]'; }

echo "==============================================================="
echo "CASE 1: a module with real classes and a real attestation -> records"
echo "(guards against a 'fix' that just makes everything fail)"
echo "==============================================================="

F1="$(_new_fixture happy)"
_stub_matrix "$F1" 0 yes
PD1="${WORKDIR}/pd-happy"
_run "$F1" "$PD1"

N1="$(_record_count "$PD1")"
if [[ "$N1" -gt 0 ]]; then
  pass "classes selected + attestation produced -> ${N1} Evidence Record(s) written"
else
  fail "classes selected + attestation produced -> ZERO Evidence Records; output: ${W_OUT}"
fi
if grep -q 'stub matrix' <<< "$W_OUT" || [[ -f "${PD1}/real-device-challenge/raw/app-invocation.log" ]]; then
  pass "fixture sanity: the sibling matrix script really was invoked"
else
  fail "fixture sanity: the sibling matrix script was never invoked, so the later cases prove less than they claim"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): zero classes selected in BOTH modules"
echo "==============================================================="
echo "The selection overrides are a documented seam. When they match nothing"
echo "in either module, no sibling script runs and no Evidence Record is"
echo "written. A Challenge wrapper that scanned nothing has proven nothing."
echo ""

F2="$(_new_fixture empty-selection)"
_stub_matrix "$F2" 0 yes
PD2="${WORKDIR}/pd-empty-selection"
_run "$F2" "$PD2" \
  LAVA_PIPELINE_CHALLENGE_APP_TEST_CLASSES=lava.app.challenges.RenamedAwayTest \
  LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES=lava.api.app.challenges.RenamedAwayTest

N2="$(_record_count "$PD2")"
if [[ "$N2" -eq 0 ]]; then
  pass "fixture sanity: this run genuinely produced zero Evidence Records"
else
  fail "fixture sanity: expected zero Evidence Records, got ${N2}; this case proves nothing"
fi
if [[ ! -f "${PD2}/real-device-challenge/raw/app-invocation.log" \
      && ! -f "${PD2}/real-device-challenge/raw/api-app-invocation.log" ]]; then
  pass "fixture sanity: neither sibling matrix script was invoked"
else
  fail "fixture sanity: a sibling matrix script WAS invoked; the fixture does not model the defect"
fi

if [[ "$W_RC" -ne 0 ]]; then
  pass "zero classes selected in both modules -> non-zero exit (${W_RC})"
else
  fail "zero classes selected in both modules -> exit 0. The wrapper ran no Challenge, invoked no matrix script, wrote no Evidence Record, and reported PASSED."
fi
if grep -qiE 'zero (Challenge )?class' <<< "$W_OUT" && grep -qiE 'refus|FAILED|proves nothing' <<< "$W_OUT"; then
  pass "the refusal names its own reason in the output"
else
  fail "zero-selection exit carries no explicit refusal message; output: ${W_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): matrix script exits 0 but writes no attestation"
echo "==============================================================="
echo "Exit 0 means 'the matrix ran and every row passed'. If no attestation"
echo "exists, the tool contradicted itself. That is not a host/registry"
echo "precondition gap, and a SKIPPED record asserting one states a cause the"
echo "wrapper cannot know."
echo ""

F3="$(_new_fixture success-without-evidence)"
_stub_matrix "$F3" 0 no
PD3="${WORKDIR}/pd-success-without-evidence"
_run "$F3" "$PD3" LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES=lava.api.app.challenges.None

if grep -q 'stub matrix' "${PD3}/real-device-challenge/raw/app-invocation.log" 2>/dev/null \
   && grep -q 'exited 0' <<< "$W_OUT"; then
  pass "fixture sanity: the matrix stub really ran and really exited 0"
else
  fail "fixture sanity: the matrix stub never ran; this case proves nothing"
fi

REC3="$(find "$PD3" -mindepth 2 -maxdepth 2 -name '*Challenge01FooTest*.json' 2>/dev/null | head -1)"
if [[ -n "$REC3" ]]; then
  pass "fixture sanity: a record was written for the discovered class"
  res3="$(jq -r '.result' "$REC3")"
  sum3="$(jq -r '.assertion_summary' "$REC3")"
  if [[ "$res3" == "FAIL" ]]; then
    pass "matrix exit 0 with no attestation -> record result FAIL (${res3})"
  else
    fail "matrix exit 0 with no attestation -> record result '${res3}'. The tool reported success and produced no evidence; that is a contract violation, not a precondition gap."
  fi
  # The bluff was the CLAIM, not the words: the original template opened
  # "BLOCKED (real, specific precondition gap -- not a feature defect)".
  # A summary that explicitly REFUSES to assert a precondition gap is the
  # honest outcome, so match the claim's own shape rather than the bare noun.
  if grep -qE '^BLOCKED|real, specific precondition gap|not a feature defect' <<< "$sum3"; then
    fail "assertion_summary asserts a BLOCKED/precondition-gap cause the wrapper cannot know about, given the matrix script exited 0: ${sum3}"
  else
    pass "assertion_summary does not assert an unknowable precondition-gap cause"
  fi
  if grep -qE 'exit(ed)? code 0|exited 0|exit 0' <<< "$sum3"; then
    pass "assertion_summary quotes the real exit code it is reasoning from"
  else
    fail "assertion_summary never mentions the real exit code that contradicts its classification: ${sum3}"
  fi
else
  fail "no Evidence Record was written for the discovered class at all"
fi

if [[ "$W_RC" -ne 0 ]]; then
  pass "matrix exit 0 with no attestation -> wrapper exits non-zero (${W_RC})"
else
  fail "matrix exit 0 with no attestation -> wrapper exits 0 and prints PASSED"
fi

echo ""
echo "==============================================================="
echo "CASE 3b: matrix exit 2 (documented host-gap) STAYS an honest SKIPPED"
echo "(guards against a 'fix' that turns every non-attestation run into FAIL)"
echo "==============================================================="

F3B="$(_new_fixture host-gap)"
_stub_matrix "$F3B" 2 no
PD3B="${WORKDIR}/pd-host-gap"
_run "$F3B" "$PD3B" LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES=lava.api.app.challenges.None

REC3B="$(find "$PD3B" -mindepth 2 -maxdepth 2 -name '*Challenge01FooTest*.json' 2>/dev/null | head -1)"
if [[ -n "$REC3B" && "$(jq -r '.result' "$REC3B")" == "SKIPPED" ]]; then
  pass "matrix exit 2 (host-gap) -> record stays SKIPPED, the documented honest outcome"
else
  fail "matrix exit 2 (host-gap) -> record result '$(jq -r '.result' "${REC3B:-/dev/null}" 2>/dev/null)', expected SKIPPED"
fi
if [[ "$W_RC" -eq 0 ]]; then
  pass "matrix exit 2 (host-gap) -> wrapper still exits 0 (SKIPPED is not a failure)"
else
  fail "matrix exit 2 (host-gap) -> wrapper exits ${W_RC}; a genuine host-gap must not be flattened into a failure"
fi

echo ""
echo "==============================================================="
echo "CASE 4: Challenge files dropped by discovery are reported, not hidden"
echo "==============================================================="

F4="$(_new_fixture partial-discovery)"
_stub_matrix "$F4" 2 no
# Two more real Challenge*Test.kt files whose package line a refactor removed.
printf '// package line lost in a refactor\nclass Challenge02LostTest\n' \
  > "${F4}/app/src/androidTest/kotlin/lava/app/challenges/Challenge02LostTest.kt"
printf '// package line lost in a refactor\nclass Challenge03LostTest\n' \
  > "${F4}/app/src/androidTest/kotlin/lava/app/challenges/Challenge03LostTest.kt"
PD4="${WORKDIR}/pd-partial-discovery"
_run "$F4" "$PD4"

if grep -qE 'Challenge02LostTest' <<< "$W_OUT" && grep -qE 'Challenge03LostTest' <<< "$W_OUT"; then
  pass "the two undiscoverable Challenge files are named in the output"
else
  fail "3 Challenge*Test.kt files are on disk and only 1 was discovered, with no mention of the 2 that were dropped. Silent coverage loss in the Challenge inventory. Output: ${W_OUT}"
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
