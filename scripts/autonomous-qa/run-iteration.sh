#!/usr/bin/env bash
# scripts/autonomous-qa/run-iteration.sh
# ---------------------------------------------------------------------------
# One QA iteration against an already-booted emulator + already-up backend:
#   1. FRESH client install (uninstall + install -r)  — clean onboarding state
#   2. start recording: logcat (cleared) + chunked screenrecord
#   3. run Challenge70 parameterized via gradle connectedDebugAndroidTest
#   4. stop recording
#   5. parse the JUnit verdict -> curated verdict.json
#
# Raw media/logs -> <evidence-dir>/raw/ (gitignored). verdict.json is curated.
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
CLIENT_PKG="digital.vasic.lava.client.dev"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_CLASS="lava.app.challenges.Challenge70AutonomousQaProviderMatrixTest"

usage() { echo "usage: $0 --backend <goapi|apiapp> --providers <csv> --query <str> --serial <serial> --api-url <url> --evidence-dir <dir>" >&2; }

BACKEND=""; PROVIDERS=""; QUERY=""; SERIAL=""; API_URL=""; EVID=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend) BACKEND="$2"; shift 2;;
    --providers) PROVIDERS="$2"; shift 2;;
    --query) QUERY="$2"; shift 2;;
    --serial) SERIAL="$2"; shift 2;;
    --api-url) API_URL="$2"; shift 2;;
    --evidence-dir) EVID="$2"; shift 2;;
    *) usage; exit 2;;
  esac
done
[[ -z "$BACKEND" || -z "$PROVIDERS" || -z "$QUERY" || -z "$SERIAL" || -z "$EVID" ]] && { usage; exit 2; }
if [[ ! -f "$APK" ]]; then
  echo "[iter] ERROR client APK missing: $APK" >&2
  # §6.J anti-bluff: a missing APK means NO test ran. Overwrite any stale
  # verdict.json with a FAIL so the matrix caller can NEVER read a prior-run PASS
  # (the missing-APK/stale-verdict PASS bluff — sixth-law-incidents 2026-07-03).
  mkdir -p "$EVID"
  printf '{"backend":"%s","providers":"%s","query":"%s","serial":"%s","gradle_rc":-1,"tests":0,"failures":0,"errors":1,"skipped":0,"marker_download_ok":false,"other_failure_signal":true,"verdict":"FAIL","note":"client APK missing at %s — build the debug APK before the iteration; NO test executed","junit_xml":"","raw_dir":"%s/raw"}\n' \
    "$BACKEND" "$PROVIDERS" "$QUERY" "$SERIAL" "$APK" "$EVID" > "$EVID/verdict.json"
  exit 2
fi

RAW="$EVID/raw"
mkdir -p "$RAW"
echo "[iter] backend=$BACKEND providers=$PROVIDERS query=$QUERY serial=$SERIAL" >&2

# 1) FRESH install (clean onboarding state every iteration).
"$ADB" -s "$SERIAL" uninstall "$CLIENT_PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null

# 2) Recording: logcat + chunked screenrecord (screenrecord caps at 180s/chunk).
"$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" logcat -v threadtime > "$RAW/logcat.txt" 2>&1 &
echo $! > "$RAW/.logcat.pid"
(
  i=0
  while :; do
    "$ADB" -s "$SERIAL" shell screenrecord --bit-rate 4000000 --time-limit 180 "/sdcard/qa_rec_${i}.mp4" >/dev/null 2>&1 || break
    "$ADB" -s "$SERIAL" pull "/sdcard/qa_rec_${i}.mp4" "$RAW/rec_${i}.mp4" >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell rm -f "/sdcard/qa_rec_${i}.mp4" >/dev/null 2>&1 || true
    i=$((i+1))
  done
) &
echo $! > "$RAW/.screenrec.pid"

stop_recording() {
  [[ -f "$RAW/.screenrec.pid" ]] && kill "$(cat "$RAW/.screenrec.pid")" 2>/dev/null || true
  "$ADB" -s "$SERIAL" shell pkill -INT screenrecord >/dev/null 2>&1 || true
  sleep 2
  for f in $("$ADB" -s "$SERIAL" shell ls /sdcard/qa_rec_*.mp4 2>/dev/null | tr -d '\r'); do
    "$ADB" -s "$SERIAL" pull "$f" "$RAW/" >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell rm -f "$f" >/dev/null 2>&1 || true
  done
  [[ -f "$RAW/.logcat.pid" ]] && kill "$(cat "$RAW/.logcat.pid")" 2>/dev/null || true
  rm -f "$RAW/.screenrec.pid" "$RAW/.logcat.pid"
}
trap stop_recording EXIT

# §6.AK: for the goapi backend the client must authenticate /v1 with the Go API's
# configured Lava-Auth key. Derive it from the SAME root .env the compose stack
# feeds the container (LAVA_AUTH_ACTIVE_CLIENTS: name:uuid). Needs the UUID only —
# NOT the HMAC secret (server base64-decodes then HMACs; client just sends base64).
# §6.H: never echo qa_key / the uuid; the derived key is credential-equivalent.
QA_KEY=""
if [[ "$BACKEND" == "goapi" ]]; then
  acl="$(grep -E '^LAVA_AUTH_ACTIVE_CLIENTS=' "$REPO_ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2-)"
  acl="${acl%\"}"; acl="${acl#\"}"        # strip optional surrounding quotes
  entry="${acl%%,*}"; uuid="${entry#*:}"  # first name:uuid → uuid
  if [[ -n "$uuid" && "$uuid" != "$entry" ]]; then
    QA_KEY="$(printf '%s' "${uuid//-/}" | xxd -r -p | base64 | tr -d '\n')"
  fi
  [[ -z "$QA_KEY" ]] && echo "[iter] NOTICE goapi backend but no LAVA_AUTH_ACTIVE_CLIENTS entry in .env — /v1 will 401; running keyless" >&2
fi

# 3) Run the parameterized Challenge (instrumentation args, one -P per key).
GRADLE_LOG="$RAW/gradle-connected.log"
# §6.J (LVA vacuous-pass sweep B14): a mtime reference stamped IMMEDIATELY
# before gradle starts. Step 4 accepts a JUnit XML only if it is newer than
# this marker, which is what makes "this run wrote it" an assertion rather than
# an assumption — androidTest-results/connected is never cleared between runs.
RUN_STARTED_AT="$RAW/.run-started-at"
: > "$RUN_STARTED_AT"
set +e
( cd "$REPO_ROOT" && ANDROID_SERIAL="$SERIAL" nice -n 10 ./gradlew \
    :app:connectedDebugAndroidTest --max-workers=2 --console=plain \
    -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
    -Pandroid.testInstrumentationRunnerArguments.qa_backend="$BACKEND" \
    -Pandroid.testInstrumentationRunnerArguments.qa_providers="$PROVIDERS" \
    -Pandroid.testInstrumentationRunnerArguments.qa_query="$QUERY" \
    -Pandroid.testInstrumentationRunnerArguments.qa_api_url="${API_URL:-https://127.0.0.1:8443}" \
    -Pandroid.testInstrumentationRunnerArguments.qa_key="$QA_KEY" \
    ) > "$GRADLE_LOG" 2>&1
GRADLE_RC=$?
set -e

# RECORDING ROBUSTNESS: guarantee at least one REAL test-UI frame is captured.
# screenrecord chunks can occasionally miss the test window (install/launcher
# only); this best-effort still-frame is taken while the app is still up,
# immediately after the run and BEFORE teardown stops recording. The logcat
# stream started before gradle (and was cleared once, not mid-run) so it holds
# the full run including the C70-RESULT marker.
"$ADB" -s "$SERIAL" exec-out screencap -p > "$RAW/final-screen.png" 2>/dev/null || true

stop_recording
trap - EXIT

# 4) Parse JUnit verdict.
RESULTS_DIR="$REPO_ROOT/app/build/outputs/androidTest-results/connected"
# §6.J evidence-freshness floor (added 2026-08-26, LVA vacuous-pass sweep B14).
#
# `find ... | head -1` selected a JUnit XML by TRAVERSAL ORDER with no freshness
# or identity assertion, and app/build/outputs/androidTest-results/connected is
# never cleared. A leftover from a previous run was therefore parsed as this
# run's result:
#
#   XML picked = .../TEST-emulator-5554.xml   mtime 2026-08-20 (6 days old)
#   parsed: tests=1 failures=0 errors=0       VERDICT = PASS
#
# Two changes: newest-first selection instead of traversal order, and a hard
# floor on the chosen file being NEWER than this run's start. RUN_STARTED_AT is
# stamped before gradle is invoked, so "newer than it" means "written by this
# invocation" — an identity assertion the verdict.json schema itself cannot make
# (run-iteration.sh:217-232 carries no timestamp and no commit SHA).
XML="$(find "$RESULTS_DIR" -name '*.xml' -type f -newer "$RUN_STARTED_AT" -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | head -1 | cut -d' ' -f2- || true)"
if [[ -z "$XML" ]]; then
  _stale_xml="$(find "$RESULTS_DIR" -name '*.xml' -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2- || true)"
  if [[ -n "$_stale_xml" ]]; then
    echo "run-iteration: REFUSING to parse a JUnit XML older than this run." >&2
    echo "  → Examined: 0 fresh XML file(s) under $RESULTS_DIR" >&2
    echo "  → Newest present: $_stale_xml (written $(date -u -r "$_stale_xml" +%Y-%m-%dT%H:%M:%SZ))" >&2
    echo "  → This run started:  $(date -u -r "$RUN_STARTED_AT" +%Y-%m-%dT%H:%M:%SZ)" >&2
    echo "  → Cause distinguished: this is NOT a failing test. Gradle wrote no new" >&2
    echo "    results — the task was UP-TO-DATE, the install failed, or the emulator" >&2
    echo "    never ran the suite. That directory is never cleared, so the leftover" >&2
    echo "    would otherwise be parsed as this run's verdict." >&2
    echo "  → Do: rm -rf '$RESULTS_DIR' and re-run, and check the gradle log." >&2
  else
    echo "run-iteration: no JUnit XML produced under $RESULTS_DIR — the suite did not run." >&2
    echo "  → Examined: 0 XML file(s); expected at least 1 from this invocation." >&2
    echo "  → Do: check the gradle log and the emulator's adb state." >&2
  fi
  # Fall through with tests=0 so the decision table below records the honest
  # "no tests executed" FAIL rather than inventing a verdict here.
  XML=""
fi
# END-OF-BLOCK §6.J evidence-freshness floor (regression-harness sentinel)
tests=0; failures=0; errors=0; skipped=0
if [[ -n "$XML" && -f "$XML" ]]; then
  tests="$(grep -oE 'tests="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
  failures="$(grep -oE 'failures="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
  errors="$(grep -oE 'errors="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
  skipped="$(grep -oE 'skipped="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
fi
# ---------------------------------------------------------------------------
# 5) VERDICT (anti-bluff decision table).
#
# The verdict is decided by the gradle/JUnit outcome ALONE. A crash is a
# failure. There is no signature, marker, or "known defect" that converts a
# failed run into a PASS.
#
# HISTORY — DO NOT RE-INTRODUCE. A PASS-override used to fire whenever the raw
# stream carried BOTH LVA-008 teardown phrases ("State must be at least
# 'CREATED'" + "Unable to destroy activity"), on the premise that the crash was
# an unfixable-upstream AndroidX defect. That premise was DISPROVEN. LVA-008
# was a Lava threading bug: under createAndroidComposeRule the Orbit
# collectSideEffect continuation resumed off the main thread, so
# navHostController.navigate(...) ran off-main; navigation-runtime inserts the
# new back-stack entry BEFORE promoting its lifecycle, and LifecycleRegistry
# .setCurrentState throws off-main — leaving the entry permanently INITIALIZED
# and producing an illegal INITIALIZED -> DESTROYED transition at Activity
# destroy. It was FIXED on 2026-06-30 by commit ccdd84c1, which wraps the
# navigate call in runOnMainThread (core/navigation/.../NavigationController.kt).
# With the cause fixed, the override was a live mechanism for reporting a
# genuine crash as green — the canonical §6.J bluff. It is removed. If that
# signature appears again it is a REGRESSION and MUST fail the iteration.
#
# marker_download_ok / other_failure_signal below are recorded as DIAGNOSTIC
# evidence only: they describe what happened during the run. They MUST NOT gate
# the verdict, and no future change may make a FAIL depend on them being false.
LOGCAT="$RAW/logcat.txt"

# Haystacks for signal greps: logcat stream + JUnit XML (either may carry it).
_hay=()
[[ -f "$LOGCAT" ]] && _hay+=("$LOGCAT")
[[ -n "$XML" && -f "$XML" ]] && _hay+=("$XML")

# marker_download_ok: the on-screen download affordance was confirmed by C70.
marker_download_ok=false
if [[ -f "$LOGCAT" ]] && grep -qE 'C70-RESULT.*DOWNLOAD-OK' "$LOGCAT" 2>/dev/null; then
  marker_download_ok=true
fi

# other_failure_signal (DIAGNOSTIC ONLY — never converts a FAIL into a PASS).
# Records whether the raw stream carried an explicit product-defect signal:
#   (a) an explicit C70 download-step failure marker, OR
#   (b) any AssertionError line.
# No phrase is whitelisted. The "State must be at least 'CREATED'" exclusion
# that used to live here belonged to the removed LVA-008 override and is gone
# with it — that ISE is now an ordinary failure signal like any other.
other_failure_signal=false
if [[ ${#_hay[@]} -gt 0 ]]; then
  if grep -qE 'C70 .*FAILED in searchTopicDownload' "${_hay[@]}" 2>/dev/null; then
    other_failure_signal=true
  fi
  if grep -qE 'AssertionError' "${_hay[@]}" 2>/dev/null; then
    other_failure_signal=true
  fi
fi

# ----- Decision table (each branch recorded via $note in verdict.json) -------
note=""
if [[ "$GRADLE_RC" -eq 0 && "$failures" -eq 0 && "$errors" -eq 0 ]]; then
  # Clean JUnit run. (Preserve the existing all-skipped -> SKIP behavior.)
  if [[ "$tests" -eq 0 ]]; then
    # gradle_rc=0 but ZERO tests executed = the Challenge never ran (bad -Ptest
    # filter, empty suite, wrong module). A no-op run asserts NOTHING, so it is
    # NOT a pass. §6.J: BUILD SUCCESSFUL is necessary, never sufficient — a green
    # with 0 executed tests is a bluff by construction.
    verdict="FAIL"; note="no tests executed (gradle_rc=0 but tests=0 — Challenge did not run)"
  elif [[ "$skipped" -gt 0 && "$tests" -eq "$skipped" ]]; then
    verdict="SKIP"; note="all tests skipped"
  else
    verdict="PASS"; note="clean"
  fi
else
  # Any non-clean gradle/JUnit outcome is a FAIL. A crash — including the
  # LVA-008 activity-destroy signature — lands here like any other failure.
  verdict="FAIL"
  note="JUnit failed (gradle_rc=$GRADLE_RC failures=$failures errors=$errors); diagnostics: marker_download_ok=$marker_download_ok other_failure_signal=$other_failure_signal"
fi

# Copy the JUnit XML into the curated evidence dir (small, tracked).
[[ -n "$XML" && -f "$XML" ]] && cp "$XML" "$EVID/junit.xml" 2>/dev/null || true

cat > "$EVID/verdict.json" <<JSON
{
  "backend": "$BACKEND",
  "providers": "$PROVIDERS",
  "query": "$QUERY",
  "serial": "$SERIAL",
  "gradle_rc": $GRADLE_RC,
  "tests": $tests, "failures": $failures, "errors": $errors, "skipped": $skipped,
  "marker_download_ok": $marker_download_ok,
  "other_failure_signal": $other_failure_signal,
  "verdict": "$verdict",
  "note": "$note",
  "junit_xml": "${XML:-}",
  "raw_dir": "$RAW"
}
JSON
echo "[iter] verdict=$verdict (tests=$tests fail=$failures err=$errors skip=$skipped rc=$GRADLE_RC)" >&2
[[ "$verdict" == "PASS" ]] && exit 0 || exit 1
