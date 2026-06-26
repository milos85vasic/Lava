#!/usr/bin/env bash
# scripts/run-genymotion-challenges.sh — Genymotion Challenge runner (§6.AH VM path).
#
# Genymotion runs Android inside a VM, so it satisfies §6.AH ("virtual devices /
# emulators MUST run in Containers or VMs — never host-direct"). This script is
# THIN GLUE: device detection + boot/stop is delegated to the Containers
# submodule's `cmd/genymotion` CLI (the §6.AG "driven by the Containers
# submodule" requirement); the Challenge install + instrumentation runs through
# Gradle's connectedDebugAndroidTest against the Genymotion adb serial.
#
# Usage:
#   scripts/run-genymotion-challenges.sh \
#     [--device "Google Pixel 9"]        # Genymotion VM name (default: first running)
#     [--test-class lava.app.challenges.Challenge00CrashSurvivalTest] \
#     [--module app|api-app]             # gradle module (default: app)
#     [--evidence-dir .lava-ci-evidence/genymotion/<ts>] \
#     [--no-build]                       # skip APK rebuild (reuse installed)
#     [--start]                          # boot the VM first if not running
#
# Exit: 0 all green; 1 a Challenge failed; 2 config/device error.
#
# §6.T.2 resource caps: gradle runs with --no-daemon --max-workers=2 nice.
# §11.4.69: a PASS here is the connectedDebugAndroidTest BUILD SUCCESSFUL on the
# real VM — captured verbatim into the evidence dir, not a metadata claim.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINERS="$ROOT/submodules/containers"
DEVICE=""
TEST_CLASS=""
MODULE="app"
EVIDENCE_DIR=""
NO_BUILD=0
DO_START=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --test-class) TEST_CLASS="$2"; shift 2 ;;
    --module) MODULE="$2"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    --no-build) NO_BUILD=1; shift ;;
    --start) DO_START=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

TS="$(date -u +%Y%m%dT%H%M%SZ)"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT/.lava-ci-evidence/genymotion/$TS}"
mkdir -p "$EVIDENCE_DIR"
# Absolutize AFTER mkdir so the directory exists for the subshell `cd`. This
# must happen BEFORE any `cd "$CONTAINERS"` consumes a relative EVIDENCE_DIR
# (e.g. GM_BIN below + every later "$EVIDENCE_DIR/..." write).
EVIDENCE_DIR="$( cd "$EVIDENCE_DIR" && pwd )"

# 1. Build the Containers genymotion CLI (thin-glue → Containers submodule).
GM_BIN="$EVIDENCE_DIR/genymotion-cli"
echo "==> building Containers genymotion CLI"
( cd "$CONTAINERS" && GOMAXPROCS=2 nice -n 19 go build -o "$GM_BIN" ./cmd/genymotion )

# 2. Detect + resolve the target VM's adb serial.
echo "==> detecting Genymotion install"
"$GM_BIN" detect | tee "$EVIDENCE_DIR/gmtool-path.txt"

if [[ "$DO_START" == "1" && -n "$DEVICE" ]]; then
  echo "==> starting Genymotion VM: $DEVICE"
  "$GM_BIN" start "$DEVICE" | tee "$EVIDENCE_DIR/start-serial.txt"
fi

"$GM_BIN" running | tee "$EVIDENCE_DIR/running-devices.tsv"
if [[ ! -s "$EVIDENCE_DIR/running-devices.tsv" ]]; then
  echo "ERROR: no running Genymotion device. Boot one in Genymotion Desktop or pass --start --device <name>." >&2
  exit 2
fi

if [[ -n "$DEVICE" ]]; then
  SERIAL="$("$GM_BIN" serial "$DEVICE")"
else
  # First running device's serial (column 2 of the TSV).
  SERIAL="$(awk -F'\t' 'NR==1{print $2; exit}' "$EVIDENCE_DIR/running-devices.tsv")"
fi
if [[ -z "$SERIAL" ]]; then
  echo "ERROR: could not resolve adb serial for device '${DEVICE:-<first running>}'." >&2
  exit 2
fi
echo "==> target Genymotion serial: $SERIAL"

# 3. Capture the device identity as §11.4.69 forensic evidence.
{
  echo "serial=$SERIAL"
  echo "android_release=$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "model=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
  echo "abi=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
} | tee "$EVIDENCE_DIR/device-identity.txt"

# 4. Run the Challenge(s) via Gradle against the Genymotion serial.
#    ANDROID_SERIAL pins adb/gradle to the VM; LAVA_REAL_DEVICE_SERIALS allows
#    the guard hook's adb-install/am-instrument path for this VM serial (§6.AH:
#    Genymotion is a VM, an authorized non-host-direct surface).
export ANDROID_SERIAL="$SERIAL"
export LAVA_REAL_DEVICE_SERIALS="$SERIAL"

# Wake + keep the VM screen on. A sleeping Genymotion screen idles the render
# pipeline -> SurfaceFlinger "Sync transaction timed out waiting for commit
# callback" -> the ComposeTestRule window-recomposer finds no committed surface
# -> spurious "No compose hierarchies found" failures even though the app
# composes fine. Forensic anchor:
# .lava-ci-evidence/sixth-law-incidents/2026-06-09-genymotion-surface-render-timeout.json
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true

# Dismiss the keyguard / notification shade. WAKEUP alone leaves the device on
# the lockscreen: dumpsys reports mCurrentFocus=NotificationShade +
# mDreamingLockscreen=true, so every launched MainActivity sits BEHIND the
# keyguard and the ComposeTestRule sees "No compose hierarchies found ... the
# Activity that calls setContent did not launch" for EVERY Challenge (a
# systemic, whole-suite false-fail — not a per-feature defect). Root cause
# diagnosed 2026-06-26: WAKEUP turns the screen on but does not unlock it.
# `wm dismiss-keyguard` clears the lockscreen (Genymotion has no secure lock);
# KEYCODE_HOME collapses any open shade so the app window becomes the
# foreground rendering surface. Verified on-device: after both, mCurrentFocus
# becomes …/MainActivity and mDreamingLockscreen=false.
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true

GRADLE_TASK=":$MODULE:connectedDebugAndroidTest"
GRADLE_ARGS=( "$GRADLE_TASK" --no-daemon --max-workers=2 )
[[ "$NO_BUILD" == "1" ]] && GRADLE_ARGS+=( -x assembleDebug )
[[ -n "$TEST_CLASS" ]] && GRADLE_ARGS+=( -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" )

echo "==> running $GRADLE_TASK on $SERIAL (test-class: ${TEST_CLASS:-ALL})"
set +e
nice -n 19 ./gradlew "${GRADLE_ARGS[@]}" 2>&1 | tee "$EVIDENCE_DIR/connected-test.log"
RC=${PIPESTATUS[0]}
set -e

# 5. Verdict — §11.4.69: PASS only on BUILD SUCCESSFUL captured verbatim.
{
  echo "# Genymotion Challenge run — $TS"
  echo "device: $SERIAL"
  echo "module: $MODULE  test-class: ${TEST_CLASS:-ALL}"
  echo "gradle exit: $RC"
  grep -aE "BUILD SUCCESSFUL|BUILD FAILED|Tests on|tests completed|FAILED" "$EVIDENCE_DIR/connected-test.log" | tail -20
} | tee "$EVIDENCE_DIR/verdict.txt"

if [[ "$RC" -eq 0 ]] && grep -qa "BUILD SUCCESSFUL" "$EVIDENCE_DIR/connected-test.log"; then
  echo "==> GENYMOTION CHALLENGE PASS (evidence: $EVIDENCE_DIR)"
  exit 0
fi
echo "==> GENYMOTION CHALLENGE FAIL (evidence: $EVIDENCE_DIR)" >&2
exit 1
