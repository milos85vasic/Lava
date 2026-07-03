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
  printf '{"backend":"%s","providers":"%s","query":"%s","serial":"%s","gradle_rc":-1,"tests":0,"failures":0,"errors":1,"skipped":0,"marker_download_ok":false,"teardown_known_lva008":false,"other_failure_signal":true,"verdict":"FAIL","note":"client APK missing at %s — build the debug APK before the iteration; NO test executed","junit_xml":"","raw_dir":"%s/raw"}\n' \
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
XML="$(find "$RESULTS_DIR" -name '*.xml' -type f 2>/dev/null | head -1 || true)"
tests=0; failures=0; errors=0; skipped=0
if [[ -n "$XML" && -f "$XML" ]]; then
  tests="$(grep -oE 'tests="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
  failures="$(grep -oE 'failures="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
  errors="$(grep -oE 'errors="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
  skipped="$(grep -oE 'skipped="[0-9]+"' "$XML" | head -1 | grep -oE '[0-9]+' || echo 0)"
fi
# ---------------------------------------------------------------------------
# 5) VERDICT ROBUSTNESS (anti-bluff decision table).
#
# Challenge70 logs `C70-RESULT ... DOWNLOAD-OK` ONLY after the user-visible
# download/magnet affordance is confirmed on screen — so that marker is hard
# evidence the real user flow reached the download. Separately, the test uses
# createAndroidComposeRule<MainActivity> which, at TEARDOWN, can crash with the
# known-open upstream defect LVA-008 (a process-FATAL
# IllegalStateException: "State must be at least 'CREATED' to be moved to
# 'DESTROYED'" / "Unable to destroy activity ... MainActivity"). That teardown
# crash marks the JUnit run failed even though the user already reached the
# download — it is NOT a product defect.
#
# We grep the raw logcat (and the JUnit XML) for both the success marker and
# the LVA-008 teardown signature, and record every signal transparently.
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

# teardown_known_lva008: logcat OR junit contains BOTH LVA-008 phrases.
teardown_known_lva008=false
if [[ ${#_hay[@]} -gt 0 ]] \
   && grep -qF "State must be at least 'CREATED'" "${_hay[@]}" 2>/dev/null \
   && grep -qF "Unable to destroy activity" "${_hay[@]}" 2>/dev/null; then
  teardown_known_lva008=true
fi

# other_failure_signal: ANY product-defect failure unrelated to LVA-008 teardown.
# This is the anti-bluff guard. A PASS-via-marker is permitted ONLY when this is
# false. Triggers:
#   (a) an explicit C70 download-step failure marker, OR
#   (b) any AssertionError line that is NOT the LVA-008 IllegalStateException.
# (The LVA-008 defect is an ISE, never an AssertionError, so any AssertionError
#  not carrying the "State must be at least 'CREATED'" phrase is a real defect.)
other_failure_signal=false
if [[ ${#_hay[@]} -gt 0 ]]; then
  if grep -qE 'C70 .*FAILED in searchTopicDownload' "${_hay[@]}" 2>/dev/null; then
    other_failure_signal=true
  fi
  if grep -hE 'AssertionError' "${_hay[@]}" 2>/dev/null \
       | grep -vF "State must be at least 'CREATED'" | grep -q .; then
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
elif [[ "$marker_download_ok" == "true" \
        && "$teardown_known_lva008" == "true" \
        && "$other_failure_signal" == "false" ]]; then
  # ANTI-BLUFF PASS-via-marker. Permitted ONLY when ALL THREE hold:
  #   1. marker_download_ok      -> the user reached the on-screen download,
  #   2. teardown_known_lva008   -> the JUnit failure IS the LVA-008 teardown,
  #   3. other_failure_signal==false -> there is NO other failure signal.
  # If any one is false we DO NOT land here; we fall through to FAIL. A real
  # assertion failure or a missing marker can therefore NEVER be reported PASS.
  verdict="PASS"
  note="flow reached download (C70-RESULT marker); JUnit failed only on known upstream LVA-008 activity-destroy teardown — not a product defect"
else
  verdict="FAIL"
  note="JUnit failed; anti-bluff override not applicable (marker_download_ok=$marker_download_ok teardown_known_lva008=$teardown_known_lva008 other_failure_signal=$other_failure_signal)"
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
  "teardown_known_lva008": $teardown_known_lva008,
  "other_failure_signal": $other_failure_signal,
  "verdict": "$verdict",
  "note": "$note",
  "junit_xml": "${XML:-}",
  "raw_dir": "$RAW"
}
JSON
echo "[iter] verdict=$verdict (tests=$tests fail=$failures err=$errors skip=$skipped rc=$GRADLE_RC)" >&2
[[ "$verdict" == "PASS" ]] && exit 0 || exit 1
