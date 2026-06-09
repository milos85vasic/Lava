#!/usr/bin/env bash
# scripts/run-release-canary.sh — §6.Z release-artifact cold-start canary (LVA-077).
#
# WHY THIS EXISTS: §6.Z mandates that the EXACT artifact about to be distributed
# is EXECUTED (not source-compiled) on a real device/VM and observed to survive
# cold-start BEFORE distribute. The 1.2.19-1039 forensic anchor was an R8-only
# crash (`painterResource` rejecting a <layer-list>) that the DEBUG variant never
# hit — proving the release (minified/R8) APK needs its own on-device canary.
#
# The existing scripts/run-genymotion-challenges.sh only runs the DEBUG variant
# via connectedDebugAndroidTest (there is no `connectedReleaseAndroidTest` — the
# app's testBuildType is debug). This script fills that gap: it installs the
# exact RELEASE APK from releases/ onto the Genymotion VM (a §6.AH-authorized
# non-host-direct surface), cold-launches it, and proves the process survives
# onCreate → first frame with NO fatal in logcat. It is THIN GLUE: VM serial
# resolution is delegated to the Containers submodule genymotion CLI, exactly
# like run-genymotion-challenges.sh (§6.AG).
#
# This is the sanctioned wrapper the §6.X guard expects — device interaction
# lives in a committed, documented script, not raw agent-loop adb. The VM serial
# is exported into LAVA_REAL_DEVICE_SERIALS so the connected path is authorized.
#
# Usage:
#   scripts/run-release-canary.sh \
#     --apk releases/1.3.1/android-release/digital.vasic.lava.client-1.3.1-release.apk \
#     --package digital.vasic.lava.client \
#     [--device "Google Pixel 9"]   # default: first running VM \
#     [--watch-seconds 25] \
#     [--evidence-dir .lava-ci-evidence/release-canary/<ts>]
#
# Exit: 0 cold-start survived (PASS); 1 crash/fatal observed (FAIL); 2 config error.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINERS="$ROOT/submodules/containers"
APK=""; PKG=""; DEVICE=""; WATCH=25; EVIDENCE_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --package) PKG="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --watch-seconds) WATCH="$2"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -z "$APK" || -z "$PKG" ]] && { echo "ERROR: --apk and --package are required." >&2; exit 2; }
[[ -f "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 2; }

TS="$(date -u +%Y%m%dT%H%M%SZ)"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT/.lava-ci-evidence/release-canary/$TS}"
mkdir -p "$EVIDENCE_DIR"
EVIDENCE_DIR="$( cd "$EVIDENCE_DIR" && pwd )"

# 1. Resolve the running Genymotion VM serial via the Containers submodule CLI.
GM_BIN="$EVIDENCE_DIR/genymotion-cli"
echo "==> building Containers genymotion CLI"
( cd "$CONTAINERS" && GOMAXPROCS=2 nice -n 19 go build -o "$GM_BIN" ./cmd/genymotion )
"$GM_BIN" detect >"$EVIDENCE_DIR/gmtool-path.txt" 2>&1 || true
"$GM_BIN" running | tee "$EVIDENCE_DIR/running-devices.tsv"
if [[ -n "$DEVICE" ]]; then
  SERIAL="$("$GM_BIN" serial "$DEVICE")"
else
  SERIAL="$(awk -F'\t' 'NR==1{print $2; exit}' "$EVIDENCE_DIR/running-devices.tsv")"
fi
[[ -n "$SERIAL" ]] || { echo "ERROR: no running Genymotion VM serial." >&2; exit 2; }
export LAVA_REAL_DEVICE_SERIALS="$SERIAL"
export ANDROID_SERIAL="$SERIAL"
echo "==> target VM serial: $SERIAL"

# 2. Device identity (§11.4.69 forensic evidence).
{
  echo "serial=$SERIAL"
  echo "android_release=$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "model=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
  echo "abi=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "apk=$APK"
  echo "apk_sha256=$(shasum -a 256 "$APK" | awk '{print $1}')"
  echo "git_sha=$(git -C "$ROOT" rev-parse HEAD)"
} | tee "$EVIDENCE_DIR/device-identity.txt"

# 3. Wake + keep screen on (sleeping VM screen → SurfaceFlinger render stall).
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true

# 4. Clean install of the EXACT release artifact.
echo "==> uninstall prior $PKG (if any)"; adb -s "$SERIAL" uninstall "$PKG" 2>&1 | tail -1 || true
echo "==> install RELEASE (R8/minified) APK"
adb -s "$SERIAL" install -r "$APK" 2>&1 | tee "$EVIDENCE_DIR/install.txt"
grep -qa "Success" "$EVIDENCE_DIR/install.txt" || { echo "ERROR: install failed." >&2; exit 1; }

# 5. Clear logcat, cold-launch the launcher activity, time-to-first-frame.
LAUNCH=$(adb -s "$SERIAL" shell cmd package resolve-activity --brief \
  -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | tail -1)
echo "launcher: $LAUNCH" | tee "$EVIDENCE_DIR/launcher.txt"
adb -s "$SERIAL" logcat -c 2>/dev/null || true
echo "==> cold-launch $LAUNCH"
adb -s "$SERIAL" shell am start -W -n "$LAUNCH" 2>&1 | tee "$EVIDENCE_DIR/am-start.txt"

# 6. Watch logcat for a fatal during the cold-start window.
echo "==> observing $WATCH s for FATAL / process death"
timeout "$WATCH" adb -s "$SERIAL" logcat -v brief "*:E" AndroidRuntime:E "$PKG":V \
  > "$EVIDENCE_DIR/logcat-window.txt" 2>&1 || true
sleep 2

# 7. Capture proof + verdict.
adb -s "$SERIAL" shell screencap -p /sdcard/canary.png >/dev/null 2>&1 || true
adb -s "$SERIAL" pull /sdcard/canary.png "$EVIDENCE_DIR/cold-start.png" >/dev/null 2>&1 || true
PID="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
RESUMED="$(adb -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -aE "mResumedActivity|ResumedActivity" | grep -a "$PKG" | head -1)"
FATAL="$(grep -aiE "FATAL EXCEPTION|AndroidRuntime.*$PKG|Process.*$PKG.*died|ANR in $PKG" "$EVIDENCE_DIR/logcat-window.txt" | head -5 || true)"

{
  echo "# Release cold-start canary — $TS"
  echo "package: $PKG"
  echo "apk: $APK"
  echo "serial: $SERIAL"
  echo "pid_after_launch: ${PID:-<none>}"
  echo "resumed_activity: ${RESUMED:-<none>}"
  echo "fatal_lines:"
  echo "${FATAL:-<none>}"
} | tee "$EVIDENCE_DIR/verdict.txt"

if [[ -n "$PID" && -z "$FATAL" ]]; then
  echo "==> RELEASE CANARY PASS — process alive (pid $PID), no fatal (evidence: $EVIDENCE_DIR)"
  exit 0
fi
echo "==> RELEASE CANARY FAIL — pid='${PID:-none}' fatal='${FATAL:-none}' (evidence: $EVIDENCE_DIR)" >&2
exit 1
