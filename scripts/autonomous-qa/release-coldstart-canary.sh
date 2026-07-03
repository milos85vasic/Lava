#!/usr/bin/env bash
# scripts/autonomous-qa/release-coldstart-canary.sh
# ---------------------------------------------------------------------------
# §6.AA / §6.Z.4 RELEASE-variant R8 cold-start canary.
#
# The project has NO testBuildType=release, so the instrumentation keystone
# (Challenge70 / Challenge00) cannot run on the R8-minified release APK. This
# canary is the release-variant equivalent of the C00 cold-start gate: it boots
# a containerized emulator (via lib-emulator, §6.AH), fresh-installs the RELEASE
# APK, `pm clear`s it, cold-launches it, and confirms it SURVIVES — the exact
# failure class of the 1.2.19-1039 forensic anchor (a release-only R8
# painterResource crash at MainActivity.setContent that the debug build did not
# reproduce).
#
# Anti-bluff verdict (all three MUST hold for PASS; a crash breaks all three):
#   1. NO "FATAL EXCEPTION" for the package in logcat after launch,
#   2. the app process is ALIVE (pidof) — a cold-start crash kills it,
#   3. the app's activity is the TOP resumed activity (dumpsys) — not just a
#      lingering background service.
#
# Usage: scripts/autonomous-qa/release-coldstart-canary.sh [--apk <path>] [--evidence-dir <dir>]
# Exit:  0 PASS · 1 FAIL (crash / not-foregrounded) · 2 setup error (APK/emulator)
# ---------------------------------------------------------------------------
set -uo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"
# shellcheck source=/dev/null
source "$QA_DIR/lib-emulator.sh"

ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
PKG="digital.vasic.lava.client"   # release variant = NO .dev suffix
APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
EVID="$REPO_ROOT/.lava-ci-evidence/1079-release-coldstart"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="$2"; shift 2;;
    --evidence-dir) EVID="$2"; shift 2;;
    *) echo "usage: $0 [--apk <path>] [--evidence-dir <dir>]" >&2; exit 2;;
  esac
done

[[ -f "$APK" ]] || { echo "[canary] FATAL release APK missing: $APK" >&2; exit 2; }
mkdir -p "$EVID/raw"
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
echo "[canary] release APK $APK (sha256 ${APK_SHA:0:16}...)" >&2

emu_cleanup_orphans
CONTAINER="$(emu_boot default)"
trap 'emu_teardown || true' EXIT
ADB_PORT="$(grep '^ADB_PORT=' "$QA_DIR/.emu-state" | cut -d= -f2)"
emu_authorize_adb "$CONTAINER"
SERIAL="$(emu_connect "$ADB_PORT")"
emu_wait_boot "$SERIAL" 360
emu_fix_network "$SERIAL"

echo "[canary] fresh install of the RELEASE APK ($PKG)" >&2
"$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null

echo "[canary] pm clear + cold launch" >&2
"$ADB" -s "$SERIAL" shell pm clear "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 20

"$ADB" -s "$SERIAL" logcat -d > "$EVID/raw/release-coldstart-logcat.txt" 2>&1 || true
"$ADB" -s "$SERIAL" exec-out screencap -p > "$EVID/raw/release-coldstart.png" 2>/dev/null || true

fatal="$(grep -cE "FATAL EXCEPTION|AndroidRuntime.*(${PKG})|Process.*${PKG}.*died|beginning of crash" "$EVID/raw/release-coldstart-logcat.txt" 2>/dev/null || true)"
fatal="${fatal:-0}"
pid="$("$ADB" -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
top="$("$ADB" -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | grep -m1 -oE "${PKG}/[^ }]+" | head -1)"

if [[ "$fatal" -eq 0 && -n "$pid" && -n "$top" ]]; then verdict="PASS"; else verdict="FAIL"; fi

cat > "$EVID/verdict.json" <<JSON
{
  "variant": "release",
  "package": "$PKG",
  "apk_sha256": "$APK_SHA",
  "fatal_count": $fatal,
  "pid": "${pid:-}",
  "top_activity": "${top:-}",
  "verdict": "$verdict",
  "note": "R8 cold-start canary: fresh install -> pm clear -> cold launch -> (no FATAL EXCEPTION) AND (process alive) AND (app activity is TOP). Covers the 1.2.19-1039 release-only R8 crash class."
}
JSON
echo "[canary] verdict=$verdict (fatal=$fatal pid=${pid:-none} top=${top:-none})" >&2
[[ "$verdict" == "PASS" ]] && exit 0 || exit 1
