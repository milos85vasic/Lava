#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test-kotlin.sh's handling
# of evidence it could not read, and of a Gradle failure no test result
# explains (audit of T021's deliverable, 2026-08-21).
#
# No real Gradle, no Android SDK, no emulator: the wrapper is pointed at a
# synthetic repo via its documented `[repo-path] [phase-dir]` argument seam,
# and that repo's `./gradlew` is a stub that writes JUnit XML in exactly the
# shape Gradle really writes (a `<testsuite>` of `<testcase>` elements under
# `<module>/build/test-results/<task>/TEST-*.xml`) and then exits with a
# chosen code. The wrapper's own `-newer $MARKER_FILE` freshness filter is
# satisfied naturally, because the stub writes the files during the run.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-21 wrapper audit):
# GRADLE_EXIT_CODE is captured and then only printed — the verdict comes
# exclusively from the parsed `<testcase>` elements. The wrapper deliberately
# passes `--continue` so that one module's failure cannot starve the others of
# their test tasks; the direct consequence is that Gradle exits non-zero for
# failures that produce NO JUnit XML at all — a Kotlin test-source compile
# error being the everyday case. Observed verbatim against a stub whose
# `:feature:onboarding:compileDebugUnitTestKotlin` "FAILED" with an
# `Unresolved reference` while every other module's XML was green:
#
#   phase-02-test-kotlin: ./gradlew --no-daemon --continue --rerun-tasks test exited 1 after 1s
#     total individual tests: 1 / PASS: 1 / FAIL: 0
#   WRAPPER EXIT = 0
#
# A whole module's tests never ran, and the category reported success.
#
# WHY CASE 3 EXISTS: the embedded XML parser catches a per-file parse error,
# prints `WARN: failed to parse ... as XML`, and CONTINUES — so a report file
# it could not read contributes nothing and blocks nothing. A JUnit XML file
# is truncated exactly when the JVM writing it dies (OOM-kill, crash, host
# power event), which is also exactly when its content is most likely to have
# been a failure. Observed verbatim with one good report and one truncated
# report whose visible content was a `<failure>`:
#
#   phase-02-test-kotlin: 2 real, freshly-written JUnit XML report file(s) found
#   phase-02-test-kotlin: parser warnings ...
#     WARN: failed to parse '.../TEST-lava.login.LoginViewModelTest.xml' as XML: unclosed token: line 4, column 4
#   phase-02-test-kotlin: 1 individual test(s) parsed from real JUnit XML
#     PASS: 1 / FAIL: 0
#   WRAPPER EXIT = 0
#
# 1 of 2 report files was unreadable and the run reported 1/1 PASS.
#
# WHY CASE 4 EXISTS: the zero-XML-files case already fails closed, but the
# zero-PARSED-TESTS case did not — reports present, no `<testcase>` recovered,
# TOTAL_TESTS=0, exit 0.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-kotlin.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
for tool in jq python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# _new_repo <name> — a synthetic repo whose ./gradlew is the stub below.
_new_repo() {
  local repo="${WORKDIR}/$1"
  mkdir -p "$repo"
  cat > "${repo}/gradlew" <<'STUB'
#!/usr/bin/env bash
# Stub gradlew: writes JUnit XML in Gradle's real report shape, then exits
# with FIXTURE_GRADLE_EXIT. Which reports it writes is fixture-controlled.
set -u
mkdir -p core/data/build/test-results/testDebugUnitTest
if [[ "${FIXTURE_EMPTY_SUITE:-0}" == "1" ]]; then
  cat > core/data/build/test-results/testDebugUnitTest/TEST-lava.data.EmptyTest.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="lava.data.EmptyTest" tests="0" skipped="0" failures="0" errors="0" time="0.0"/>
XML
else
  cat > core/data/build/test-results/testDebugUnitTest/TEST-lava.data.EndpointConverterTest.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="lava.data.EndpointConverterTest" tests="1" skipped="0" failures="0" errors="0" time="0.012">
  <testcase name="round-trips a GoApi endpoint with an explicit port" classname="lava.data.EndpointConverterTest" time="0.012"/>
</testsuite>
XML
fi
echo "> Task :core:data:testDebugUnitTest"
if [[ "${FIXTURE_FAILING_TEST:-0}" == "1" ]]; then
  mkdir -p feature/login/build/test-results/testDebugUnitTest
  cat > feature/login/build/test-results/testDebugUnitTest/TEST-lava.login.LoginViewModelTest.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="lava.login.LoginViewModelTest" tests="1" skipped="0" failures="1" errors="0" time="0.02">
  <testcase name="clears stale serviceUnavailable on UsernameChanged" classname="lava.login.LoginViewModelTest" time="0.02">
    <failure message="expected null but was ServiceUnavailable" type="java.lang.AssertionError">java.lang.AssertionError: expected null but was ServiceUnavailable</failure>
  </testcase>
</testsuite>
XML
fi
if [[ "${FIXTURE_TRUNCATED_XML:-0}" == "1" ]]; then
  mkdir -p feature/login/build/test-results/testDebugUnitTest
  # Exactly what a JVM killed mid-write leaves behind: a report whose visible
  # content is a real <failure>, cut off before the closing tags.
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="lava.login.LoginViewModelTest" tests="7" failures="1">\n  <testcase name="rejects blank username" classname="lava.login.LoginViewModelTest" time="0.01">\n    <failure message="expected true' \
    > feature/login/build/test-results/testDebugUnitTest/TEST-lava.login.LoginViewModelTest.xml
fi
if [[ "${FIXTURE_COMPILE_FAILURE:-0}" == "1" ]]; then
  echo "> Task :feature:onboarding:compileDebugUnitTestKotlin FAILED"
  echo "e: OnboardingViewModelTest.kt:41:9 Unresolved reference: assertProviderProbed"
  echo "FAILURE: Build completed with 1 failure."
fi
exit "${FIXTURE_GRADLE_EXIT:-0}"
STUB
  chmod +x "${repo}/gradlew"
  printf '%s' "$repo"
}

# _run_kotlin <repo> -> sets RC, OUT, PHASE
_run_kotlin() {
  local repo="$1"; shift
  PHASE="${repo}/phase-02"
  local out="${WORKDIR}/kotlin-out.log"
  set +e
  env "$@" bash "$WRAPPER" "$repo" "$PHASE" >"$out" 2>&1
  RC=$?
  set -e
  OUT="$(cat "$out")"
}

_records() { find "$1" -name '*.json' -not -path '*/raw/*' 2>/dev/null | sort; }

echo "==============================================================="
echo "CASE 1: real reports -> honest verdicts (anti-'fail everything')"
echo "==============================================================="

R1="$(_new_repo green)"
_run_kotlin "$R1" FIXTURE_GRADLE_EXIT=0
if [[ "$RC" -eq 0 ]]; then
  pass "one passing testcase + gradle exit 0 -> wrapper exit 0"
else
  fail "one passing testcase + gradle exit 0 -> exit ${RC}; output: ${OUT}"
fi
n="$(_records "$PHASE" | wc -l | tr -d ' ')"
if [[ "$n" -eq 1 ]]; then
  pass "one passing testcase -> exactly 1 Evidence Record"
else
  fail "one passing testcase -> ${n} Evidence Records, expected 1"
fi

R2="$(_new_repo redtest)"
_run_kotlin "$R2" FIXTURE_GRADLE_EXIT=1 FIXTURE_FAILING_TEST=1
if [[ "$RC" -ne 0 ]]; then
  pass "a real <failure> testcase -> non-zero exit (${RC})"
else
  fail "a real <failure> testcase -> exit 0; output: ${OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): Gradle exits non-zero, every parsed test PASSes"
echo "==============================================================="

R3="$(_new_repo compilefail)"
_run_kotlin "$R3" FIXTURE_GRADLE_EXIT=1 FIXTURE_COMPILE_FAILURE=1

if grep -q "test exited 1" <<< "$OUT"; then
  pass "fixture sanity: the wrapper really did observe a non-zero Gradle exit"
else
  fail "fixture sanity: the wrapper never saw a non-zero Gradle exit; output: ${OUT}"
fi

if [[ "$RC" -ne 0 ]]; then
  pass "unexplained non-zero Gradle exit -> non-zero wrapper exit (${RC})"
else
  fail "unexplained non-zero Gradle exit -> exit 0. A module whose test sources failed to compile ran no tests at all, produced no JUnit XML, and the category still reported PASS."
fi

explained=0
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  if [[ "$(jq -r '.result' "$r")" == "FAIL" ]]; then
    if grep -qi 'gradle\|Unresolved reference\|compileDebugUnitTestKotlin' <<< "$(jq -r '.assertion_summary' "$r")"; then
      explained=1
    fi
  fi
done < <(_records "$PHASE")
if [[ "$explained" -eq 1 ]]; then
  pass "an Evidence Record records the Gradle failure, quoting its real output"
else
  fail "no FAIL Evidence Record explains the non-zero Gradle exit — it is invisible to phase-02-test.sh's evidence scan and to the run report"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): one report file could not be parsed"
echo "==============================================================="

R4="$(_new_repo truncated)"
_run_kotlin "$R4" FIXTURE_GRADLE_EXIT=0 FIXTURE_TRUNCATED_XML=1

if grep -q "2 real, freshly-written JUnit XML report file(s) found" <<< "$OUT"; then
  pass "fixture sanity: the wrapper really did find 2 report files"
else
  fail "fixture sanity: expected 2 report files to be found; output: ${OUT}"
fi

if [[ "$RC" -ne 0 ]]; then
  pass "an unparseable report file -> non-zero exit (${RC})"
else
  fail "an unparseable report file -> exit 0 reporting 1/1 PASS. Half the evidence was unreadable — and a truncated report is truncated precisely when the JVM writing it died — yet the run reported complete success over the half it could read."
fi

# The record must (a) be a FAIL, (b) name the exact report file that could
# not be read, and (c) say it could not be parsed — a record that merely
# exists proves nothing about which evidence was lost.
named=0
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  [[ "$(jq -r '.result' "$r")" == "FAIL" ]] || continue
  id="$(jq -r '.test_id' "$r")"
  summary="$(jq -r '.assertion_summary' "$r")"
  if grep -q 'TEST-lava.login.LoginViewModelTest.xml' <<< "${id}${summary}" \
     && grep -qi 'could not be parsed\|unparseable\|failed to parse' <<< "${id}${summary}"; then
    named=1
    if [[ "$(jq -r '.anti_bluff_status' "$r")" == "validated" ]]; then
      pass "the unreadable-report record survives anti-bluff validation"
    else
      fail "the unreadable-report record was rejected: $(jq -r '.anti_bluff_status' "$r")"
    fi
  fi
done < <(_records "$PHASE")
if [[ "$named" -eq 1 ]]; then
  pass "a FAIL Evidence Record names the exact report file that could not be read"
else
  fail "no FAIL Evidence Record names the unreadable report file — the loss is a console WARN only"
fi

echo ""
echo "==============================================================="
echo "CASE 4: reports present, zero <testcase> elements parsed"
echo "==============================================================="

R5="$(_new_repo emptysuite)"
_run_kotlin "$R5" FIXTURE_GRADLE_EXIT=0 FIXTURE_EMPTY_SUITE=1

if [[ "$RC" -ne 0 ]]; then
  pass "zero parsed tests -> non-zero exit (${RC})"
else
  fail "zero parsed tests -> exit 0 with zero Evidence Records; the kotlin-unit category proved nothing and reported success"
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
