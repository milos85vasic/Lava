#!/usr/bin/env bash
# scripts/autonomous-qa/run-nav-challenges.sh
# ---------------------------------------------------------------------------
# Boots ONE containerized-KVM emulator (via lib-emulator, the same path the
# autonomous-QA matrix uses) and runs the §6.AK cycle-coverage-map's supporting
# nav Challenges (C24 / C46 / C55) against the already-built debug APK, then
# tears the emulator down. Captures the JUnit XML + a per-test verdict so the
# §6.Z device evidence can record each Challenge's pass/fail with a real device.
#
# Usage: run-nav-challenges.sh <evidence-dir> <fqn>[ <fqn>...]
# ---------------------------------------------------------------------------
set -uo pipefail
QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
CLIENT_PKG="digital.vasic.lava.client.dev"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

EVID="$1"; shift
mkdir -p "$EVID/raw"
[[ -f "$APK" ]] || { echo "ERROR client APK missing: $APK" >&2; exit 2; }

source "$QA_DIR/lib-subsets.sh"
source "$QA_DIR/lib-emulator.sh"

SERIAL=""
cleanup() { emu_teardown || true; }
trap cleanup EXIT

emu_cleanup_orphans
CONTAINER="$(emu_boot default)"
ADB_PORT="$(grep '^ADB_PORT=' "$QA_DIR/.emu-state" | cut -d= -f2)"
emu_authorize_adb "$CONTAINER"
SERIAL="$(emu_connect "$ADB_PORT")"
emu_wait_boot "$SERIAL" 360
emu_fix_network "$SERIAL"

# Fresh install (clean state).
"$ADB" -s "$SERIAL" uninstall "$CLIENT_PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null

# Grant POST_NOTIFICATIONS up front. Otherwise LeakCanary (debug-only) launches
# its RequestPermissionActivity mid-test → the system GrantPermissionsActivity
# pops OVER MainActivity → MainActivity PAUSED/STOPPED/DESTROYED → the next
# Compose query throws "No compose hierarchies found". Harness-side, like the
# keyguard dismiss. (API 33+ only; ignored on older targets.)
"$ADB" -s "$SERIAL" shell pm grant "$CLIENT_PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

# Dismiss the keyguard + wake the screen BEFORE instrumentation. A cold-booted
# emulator can present the lockscreen, so the first Challenge's MainActivity has
# no reachable compose hierarchy → "No compose hierarchies found" (the 1077
# keyguard incident; run-genymotion-challenges.sh applies the same fix).
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true

# Capture logcat for the run (so search/nav flows can be debugged).
"$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" logcat -v threadtime > "$EVID/raw/logcat.txt" 2>&1 &
LOGCAT_PID=$!
trap '{ kill "$LOGCAT_PID" 2>/dev/null || true; emu_teardown || true; }' EXIT

# Android filters instrumentation by class via the runner argument (comma-
# separated FQNs), NOT gradle's --tests. Mirrors run-iteration.sh.
CLASS_LIST="$(IFS=','; echo "$*")"

GRADLE_LOG="$EVID/raw/gradle-nav.log"
( cd "$REPO_ROOT" && ANDROID_SERIAL="$SERIAL" nice -n 10 ./gradlew \
    :app:connectedDebugAndroidTest --max-workers=2 --console=plain \
    -Pandroid.testInstrumentationRunnerArguments.class="$CLASS_LIST" ) > "$GRADLE_LOG" 2>&1
GRADLE_RC=$?

# Collect the JUnit XML (curated evidence).
RESULTS_DIR="$REPO_ROOT/app/build/outputs/androidTest-results/connected"
find "$RESULTS_DIR" -name '*.xml' -exec cp {} "$EVID/" \; 2>/dev/null || true

echo "gradle_rc=$GRADLE_RC"
echo "junit copied to $EVID"
exit $GRADLE_RC
