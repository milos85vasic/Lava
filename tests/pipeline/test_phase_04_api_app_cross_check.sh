#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-04-live-verify-api-app.sh's
# TWO-INDEPENDENT-SOURCES cross-check.
#
# No emulator, no container, no Gradle. The container runtime is a stub
# (LAVA_PIPELINE_LIVE_VERIFY_API_APP_CONTAINER_RUNTIME, a documented seam) and
# scripts/run-api-app-challenge-matrix.sh is replaced by a stub that writes
# exactly the artifacts a real run would leave behind — the Containers
# attestation JSON and/or Gradle's host-side JUnit XML — so what is under test
# is the wrapper's own reasoning about those two sources.
#
# WHY CASES 2 AND 3 EXIST (forensic anchor, 2026-08-22):
# The script's own header states the contract in one sentence:
#
#   "PASS only when BOTH the freshly-written host-side Gradle JUnit XML shows
#    the class's testcases with no <failure>/<error> AND the Containers
#    attestation row for it reports test_passed=true. Disagreement between
#    those two independent sources is itself a FAIL (a matrix runner that
#    claimed green while Gradle's own report showed a failure would be
#    precisely the bluff FR-004 exists to catch)."
#
# The implementation only compared the two verdicts when BOTH existed:
#
#   xml_verdict = "PASS" if not xml_failures else "FAIL"   # None when no testcases
#   row_verdict = "PASS" if row.get("test_passed") else "FAIL"  # None when no row
#   if xml_verdict and row_verdict and xml_verdict != row_verdict: FAIL
#   elif (xml_verdict or row_verdict) == "FAIL":                  FAIL
#   else:                                                          PASS
#
# so a run in which one source produced NOTHING AT ALL fell through to PASS.
# Disagreement was caught; total absence was not — and absence is the weaker
# evidential position, not the stronger one. Two real observed outcomes:
#
#   CASE 2 — attestation says test_passed=true, Gradle wrote no XML. The phase
#   exited 0 announcing "cross-checked against two independent sources" while
#   its own record read "Gradle's own host-side JUnit XML records 0 real
#   testcase(s) with no <failure>/<error>: ". The independent corroboration
#   the header says exists to catch a lying matrix runner was simply gone, and
#   the matrix runner's unverified self-report carried the verdict alone.
#
#   CASE 3 — Gradle XML is green, the attestation has no row for the class
#   (e.g. the Containers CLI renames or drops `test_class`). The PASS summary
#   then printed "and the INDEPENDENT Containers attestation row agrees
#   (test_passed=true, ...)" — where `test_passed=true` is a LITERAL in the
#   format string, not a value read from any row, and `(row or {})` silently
#   supplied an empty dict for the rest. The record asserted that a source
#   agreed when that source produced nothing at all.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE04="${REPO_ROOT}/scripts/pipeline/phase-04-live-verify-api-app.sh"

if [[ ! -f "$PHASE04" ]]; then
  echo "FAIL: script under test not found: $PHASE04"
  exit 1
fi
for tool in jq python3 git timeout; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

FQCN="lava.api.app.challenges.Challenge02ApiAppBootAndServeTest"
IMAGE="ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64"

# A stub container runtime. `image inspect` succeeds for the api34 image so the
# script picks a real-looking target; `ps` reports one running lava-emu-*
# container so the script's own §6.AH container proof is satisfied and cannot
# mask the cross-check outcome under test; `ps -a` reports no survivors.
mkdir -p "${WORKDIR}/bin"
cat > "${WORKDIR}/bin/stubruntime" <<STUBRT
#!/usr/bin/env bash
if [[ "\$1 \$2" == "image inspect" ]]; then
  [[ "\$3" == *api34-x86_64 ]] && exit 0 || exit 1
fi
if [[ "\$1" == "ps" ]]; then
  [[ "\$2" == "-a" ]] && exit 0
  echo "lava-emu-fixture | image=${IMAGE} | status=Up 3s | ports=n/a"
  exit 0
fi
exit 0
STUBRT
chmod +x "${WORKDIR}/bin/stubruntime"

# _new_fixture <name> <attestation:row|rowfail|norow|none> <xml:green|failing|none>
_new_fixture() {
  local name="$1" attest="$2" xml="$3"
  local f="${WORKDIR}/${name}"
  mkdir -p "${f}/scripts" \
           "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges" \
           "${f}/api-app/build/outputs/apk/debug"

  cat > "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges/Challenge02ApiAppBootAndServeTest.kt" <<'KT'
package lava.api.app.challenges
/**
 * FALSIFIABILITY REHEARSAL: forced the on-device embed's /health route to
 * return 500; this Challenge failed with "expected 200 but was 500".
 */
class Challenge02ApiAppBootAndServeTest
KT
  printf 'stand-in for the real :api-app debug APK\n' \
    > "${f}/api-app/build/outputs/apk/debug/api-app-debug.apk"

  # The matrix stub leaves behind exactly the artifacts each scenario models.
  cat > "${f}/scripts/run-api-app-challenge-matrix.sh" <<MSTUB
#!/usr/bin/env bash
ev=""
while [[ \$# -gt 0 ]]; do case "\$1" in --evidence-dir) ev="\$2"; shift 2;; *) shift;; esac; done
echo "containerized runner: podman"
mkdir -p "\$ev"

case "${attest}" in
  row)   cat > "\$ev/real-device-verification.json" <<'J'
{"gating": true, "rows": [{"avd": "CZ_API34_Phone", "api_level": 34,
  "test_class": "${FQCN}", "test_passed": true, "boot_seconds": 41,
  "test_seconds": 12, "concurrent": 1,
  "diag": {"target": "emulator", "sdk": "34", "device": "CZ_API34_Phone",
           "adb_devices_state": "device"}}]}
J
         ;;
  rowfail) cat > "\$ev/real-device-verification.json" <<'J'
{"gating": true, "rows": [{"avd": "CZ_API34_Phone", "api_level": 34,
  "test_class": "${FQCN}", "test_passed": false,
  "test_error": "instrumentation reported 1 failure",
  "diag": {"sdk": "34", "device": "CZ_API34_Phone"}}]}
J
         ;;
  norow) cat > "\$ev/real-device-verification.json" <<'J'
{"gating": true, "rows": [{"avd": "CZ_API34_Phone", "api_level": 34,
  "test_passed": true, "diag": {"sdk": "34", "device": "CZ_API34_Phone"}}]}
J
         ;;
esac

xmldir="api-app/build/outputs/androidTest-results/connected/debug"
case "${xml}" in
  green)   mkdir -p "\$xmldir"
           cat > "\$xmldir/TEST-stub.xml" <<'X'
<testsuite name="stub" tests="1" failures="0" errors="0">
  <testcase classname="${FQCN}" name="bootsAndServesOverHttps" time="9.5"/>
</testsuite>
X
           ;;
  failing) mkdir -p "\$xmldir"
           cat > "\$xmldir/TEST-stub.xml" <<'X'
<testsuite name="stub" tests="1" failures="1" errors="0">
  <testcase classname="${FQCN}" name="bootsAndServesOverHttps" time="9.5">
    <failure type="java.lang.AssertionError" message="expected 200 but was 500">stack</failure>
  </testcase>
</testsuite>
X
           ;;
esac

# Long enough for the wrapper's own §6.AH proof poller to take one sample.
sleep 2
exit 0
MSTUB
  chmod +x "${f}/scripts/run-api-app-challenge-matrix.sh"

  git init -q -b master "$f"
  git -C "$f" config user.email "fixture@example.invalid"
  git -C "$f" config user.name "Fixture"
  printf '%s' "$f"
}

# _run <fixture> <run_id> — sets P4_RC, P4_OUT, P4_RECORD (the challenge record).
_run() {
  local f="$1" run_id="$2"
  ( cd "$f" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
      && init_run_report "$run_id" "$(printf '0%.0s' {1..40})" >/dev/null )
  local out="${WORKDIR}/p4.log"
  set +e
  PATH="${WORKDIR}/bin:$PATH" \
  LAVA_PIPELINE_LIVE_VERIFY_API_APP_CONTAINER_RUNTIME=stubruntime \
  LAVA_PIPELINE_LIVE_VERIFY_API_APP_TIMEOUT_SECONDS=120 \
    bash "$PHASE04" "$run_id" "$f" >"$out" 2>&1
  P4_RC=$?
  set -e
  P4_OUT="$(cat "$out")"
  P4_RECORD="${f}/.lava-ci-evidence/pipeline-runs/${run_id}/phase-04/real-device-challenge/${FQCN}.json"
}

echo "==============================================================="
echo "CASE 1: both sources present and green -> PASS"
echo "(guards against a 'fix' that just makes every run fail)"
echo "==============================================================="

F1="$(_new_fixture both-green row green)"
_run "$F1" "2026-08-22T10-00-00Z"

if [[ -f "$P4_RECORD" ]]; then
  pass "fixture sanity: a real-device-challenge record was written"
  r1="$(jq -r '.result' "$P4_RECORD")"
  if [[ "$r1" == "PASS" ]]; then
    pass "attestation row test_passed=true + green Gradle XML -> record PASS"
  else
    fail "both sources green -> record result '${r1}', expected PASS. A fix must not turn genuine agreement into a failure."
  fi
else
  fail "fixture sanity: no record at ${P4_RECORD}; output: ${P4_OUT}"
fi
if [[ "$P4_RC" -eq 0 ]]; then
  pass "both sources green -> phase exits 0"
else
  fail "both sources green -> phase exits ${P4_RC}; output: ${P4_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): attestation says PASS, Gradle wrote NO XML"
echo "==============================================================="
echo "The Gradle JUnit XML is the source the header calls independent of the"
echo "matrix runner's self-report. With it absent there is exactly one source,"
echo "and it is the one being cross-checked."
echo ""

F2="$(_new_fixture attestation-only row none)"
_run "$F2" "2026-08-22T11-00-00Z"

if [[ -f "$P4_RECORD" ]]; then
  pass "fixture sanity: a record was written"
  sum2="$(jq -r '.assertion_summary' "$P4_RECORD")"
  r2="$(jq -r '.result' "$P4_RECORD")"
  if grep -q '0 real testcase' <<< "$sum2" && [[ "$r2" == "PASS" ]]; then
    fail "attestation-only -> record PASS whose own summary says '0 real testcase(s)'. A PASS derived from zero parsed testcases is the canonical no-match vacuous pass."
  elif [[ "$r2" == "PASS" ]]; then
    fail "attestation-only -> record PASS. The header requires BOTH sources; only the matrix runner's own self-report existed."
  else
    pass "attestation-only -> record result ${r2} (not PASS)"
  fi
  if grep -qiE 'Missing:.*JUnit XML' <<< "$sum2"; then
    pass "assertion_summary names the missing source explicitly"
  else
    fail "assertion_summary does not say which of the two sources was missing: ${sum2}"
  fi
else
  fail "fixture sanity: no record written; output: ${P4_OUT}"
fi
if [[ "$P4_RC" -ne 0 ]]; then
  pass "attestation-only -> phase exits non-zero (${P4_RC})"
else
  fail "attestation-only -> phase exits 0 and announces 'cross-checked against two independent sources'"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): Gradle XML green, attestation has NO row"
echo "==============================================================="
echo "Models the Containers CLI dropping or renaming its row's test_class."
echo "The PASS summary hardcoded 'test_passed=true' as literal text, so it"
echo "asserted the attestation agreed when no row existed to agree."
echo ""

F3="$(_new_fixture xml-only norow green)"
_run "$F3" "2026-08-22T12-00-00Z"

if [[ -f "$P4_RECORD" ]]; then
  pass "fixture sanity: a record was written"
  sum3="$(jq -r '.assertion_summary' "$P4_RECORD")"
  r3="$(jq -r '.result' "$P4_RECORD")"
  if [[ "$r3" == "PASS" ]]; then
    fail "no attestation row -> record PASS. The header requires BOTH sources."
  else
    pass "no attestation row -> record result ${r3} (not PASS)"
  fi
  if grep -q 'test_passed=true' <<< "$sum3"; then
    fail "assertion_summary claims 'test_passed=true' although NO attestation row exists for this class — a fabricated corroboration: ${sum3}"
  else
    pass "assertion_summary does not fabricate a 'test_passed=true' corroboration"
  fi
  if grep -qiE 'Missing:.*attestation row' <<< "$sum3"; then
    pass "assertion_summary names the missing source explicitly"
  else
    fail "assertion_summary does not say which of the two sources was missing: ${sum3}"
  fi
else
  fail "fixture sanity: no record written; output: ${P4_OUT}"
fi
if [[ "$P4_RC" -ne 0 ]]; then
  pass "no attestation row -> phase exits non-zero (${P4_RC})"
else
  fail "no attestation row -> phase exits 0"
fi

echo ""
echo "==============================================================="
echo "CASE 4: both sources AGREE on a real failure -> FAIL quoting it"
echo "(guards against a fix that stops reading the XML's verdict at all)"
echo "==============================================================="

F4="$(_new_fixture real-failure rowfail failing)"
_run "$F4" "2026-08-22T13-00-00Z"

if [[ -f "$P4_RECORD" ]]; then
  r4="$(jq -r '.result' "$P4_RECORD")"
  sum4="$(jq -r '.assertion_summary' "$P4_RECORD")"
  if [[ "$r4" == "FAIL" ]]; then
    pass "both sources agreeing on a real failure -> record FAIL"
  else
    fail "both sources agreeing on a real failure -> record '${r4}', expected FAIL"
  fi
  if grep -q 'expected 200 but was 500' <<< "$sum4"; then
    pass "assertion_summary quotes the real failure message from the XML"
  else
    fail "assertion_summary does not quote the real failure message: ${sum4}"
  fi
else
  fail "fixture sanity: no record written; output: ${P4_OUT}"
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
