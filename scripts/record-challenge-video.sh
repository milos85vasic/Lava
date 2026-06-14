#!/usr/bin/env bash
# scripts/record-challenge-video.sh — record + validate ONE Compose UI Challenge
# on the Genymotion VM, deliver the SUCCESS video to the operator's home dir.
#
# WHAT THIS DOES (thin glue, §6.AH VM path, §6.U no-sudo):
#   1. Resolve the Genymotion VM adb serial via the Containers submodule
#      `cmd/genymotion` CLI (the §6.AG "driven by the Containers submodule"
#      requirement) — the VM is an authorized non-host-direct surface (§6.AH:
#      "virtual devices / emulators MUST run in Containers or VMs"). NEVER a
#      live/physical ADB device (§6.AG: those are used by other projects).
#   2. Start an `adb shell screenrecord` capture on that serial (the recorder
#      is read-only w.r.t. the app under test — no pm clear / am force-stop).
#   3. Run the SINGLE named Challenge via `:app:connectedDebugAndroidTest`
#      with the `-Pandroid.testInstrumentationRunnerArguments.class=` filter —
#      the SAME invocation `run-genymotion-challenges.sh` uses.
#   4. Stop the recording, pull the `.mp4` off the device into the §11.4.128
#      device-recording tree, and copy it to `<name>.mp4` in the evidence dir.
#   5. VERDICT (§11.4.69 / §6.J): PASS only when gradle exits 0 AND the verbatim
#      string `BUILD SUCCESSFUL` is present in the captured connected-test log.
#   6. ON PASS ONLY: copy `<name>.mp4` to $HOME/<name>.mp4 AND
#      $HOME/Downloads/<name>.mp4 (creating Downloads if missing). ON FAIL the
#      video is NOT delivered — only success videos reach the home dir, so a
#      delivered video is itself proof the Challenge passed on the VM.
#
# WHY THE VIDEO IS THE WATCHABLE PROOF (honest capability statement):
#   The connectedAndroidTest PASS is the GROUND TRUTH (§6.J: a Challenge that
#   asserts on user-visible Compose state and goes BUILD SUCCESSFUL means a real
#   user can complete the flow). The recorded `.mp4` is the human-watchable
#   artifact of that exact run — the operator can watch the screen do what the
#   Challenge asserted. HelixQA's `cmd/recording-analyzer` (OCR-per-frame +
#   §11.4.107 frozen/stale liveness verdict) and `pkg/analysis` LLM-vision can
#   be layered ON TOP as a deeper auto-validation pass (see
#   docs/qa/video-challenge-recording.md), but they are NOT in the gate path of
#   this script: the test verdict gates delivery; the video documents it. This
#   is the most rigorous REAL alternative — it makes no claim the tooling cannot
#   honestly back (no fabricated "the AI watched the video and it's fine").
#
# Usage:
#   scripts/record-challenge-video.sh \
#     --test-class lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest \
#     --name search-select-all \
#     [--device "Google Pixel 9"]   # Genymotion VM name (default: first running)
#     [--module app|api-app]        # gradle module (default: app)
#     [--serial 127.0.0.1:6555]     # explicit serial; skips Containers detection
#     [--evidence-dir <dir>]        # default: .lava-ci-evidence/challenge-video/<ts>
#     [--no-build]                  # reuse installed APK (skip assembleDebug)
#     [--start]                     # boot the VM first if not running
#
# Positional shorthand (in this order, both required):
#   scripts/record-challenge-video.sh <FULLY.QUALIFIED.TestClass> <output-name>
#
# Exit: 0 PASS + video delivered; 1 Challenge FAILED (no video delivered);
#       2 config / usage / device error.
#
# §6.T.2 resource caps: gradle runs --no-daemon --max-workers=2 under nice.
# §6.R no-hardcoding: no host:port literal beyond $HOME / the operator-supplied
#   --serial; the Genymotion serial is RESOLVED, the home dir comes from $HOME.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINERS="$ROOT/submodules/containers"
RECORDER="$ROOT/scripts/record-device-session.sh"

TEST_CLASS=""
NAME=""
DEVICE=""
MODULE="app"
SERIAL_ARG=""
EVIDENCE_DIR=""
NO_BUILD=0
DO_START=0

usage() { sed -n '2,60p' "${BASH_SOURCE[0]}" >&2; }

# --- arg parse: named flags first, then accept two positionals as class + name.
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --test-class) TEST_CLASS="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --module) MODULE="$2"; shift 2 ;;
    --serial) SERIAL_ARG="$2"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    --no-build) NO_BUILD=1; shift ;;
    --start) DO_START=1; shift ;;
    -h|--help|help) usage; exit 0 ;;
    --*) echo "unknown arg: $1" >&2; usage; exit 2 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done

# Positional shorthand: <class> <name>.
if [[ -z "$TEST_CLASS" && "${#POSITIONAL[@]}" -ge 1 ]]; then TEST_CLASS="${POSITIONAL[0]}"; fi
if [[ -z "$NAME"       && "${#POSITIONAL[@]}" -ge 2 ]]; then NAME="${POSITIONAL[1]}"; fi

if [[ -z "$TEST_CLASS" || -z "$NAME" ]]; then
  echo "ERROR: both a Challenge test class and an output name are required." >&2
  echo "  e.g. $0 --test-class lava.app.challenges.Challenge01... --name search-select-all" >&2
  usage
  exit 2
fi

# Sanitize the output name into a filesystem-safe token (keep [A-Za-z0-9._-]).
# Prevents path traversal / weird chars from reaching $HOME copies.
SAFE_NAME="${NAME//[^A-Za-z0-9._-]/-}"
if [[ -z "$SAFE_NAME" ]]; then
  echo "ERROR: --name '$NAME' sanitizes to empty; pick an alphanumeric name." >&2
  exit 2
fi

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not on PATH." >&2; exit 2; }
[[ -x "$RECORDER" ]] || { echo "ERROR: recorder not found/executable: $RECORDER" >&2; exit 2; }

TS="$(date -u +%Y%m%dT%H%M%SZ)"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT/.lava-ci-evidence/challenge-video/$TS}"
mkdir -p "$EVIDENCE_DIR"
EVIDENCE_DIR="$(cd "$EVIDENCE_DIR" && pwd)"   # absolutize before any `cd`

# ---------------------------------------------------------------------------
# 1. Resolve the Genymotion VM adb serial (Containers submodule = §6.AG).
# ---------------------------------------------------------------------------
SERIAL=""
if [[ -n "$SERIAL_ARG" ]]; then
  SERIAL="$SERIAL_ARG"
  echo "==> using operator-supplied serial: $SERIAL"
else
  GM_BIN="$EVIDENCE_DIR/genymotion-cli"
  echo "==> building Containers genymotion CLI (thin-glue → Containers submodule)"
  ( cd "$CONTAINERS" && GOMAXPROCS=2 nice -n 19 go build -o "$GM_BIN" ./cmd/genymotion )

  echo "==> detecting Genymotion install"
  "$GM_BIN" detect | tee "$EVIDENCE_DIR/gmtool-path.txt"

  if [[ "$DO_START" == "1" && -n "$DEVICE" ]]; then
    echo "==> starting Genymotion VM: $DEVICE"
    "$GM_BIN" start "$DEVICE" | tee "$EVIDENCE_DIR/start-serial.txt"
  fi

  "$GM_BIN" running | tee "$EVIDENCE_DIR/running-devices.tsv"
  if [[ ! -s "$EVIDENCE_DIR/running-devices.tsv" ]]; then
    echo "ERROR: no running Genymotion VM. Boot one in Genymotion Desktop or pass --start --device <name>." >&2
    exit 2
  fi

  if [[ -n "$DEVICE" ]]; then
    SERIAL="$("$GM_BIN" serial "$DEVICE")"
  else
    SERIAL="$(awk -F'\t' 'NR==1{print $2; exit}' "$EVIDENCE_DIR/running-devices.tsv")"
  fi
fi

if [[ -z "$SERIAL" ]]; then
  echo "ERROR: could not resolve a Genymotion adb serial for device '${DEVICE:-<first running>}'." >&2
  exit 2
fi
echo "==> target Genymotion serial: $SERIAL"

# Device identity (read-only forensic evidence).
{
  echo "serial=$SERIAL"
  echo "challenge=$TEST_CLASS"
  echo "output_name=$SAFE_NAME"
  echo "android_release=$(adb -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "sdk=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "model=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "abi=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
} | tee "$EVIDENCE_DIR/device-identity.txt"

# Wake + keep the VM screen on. A sleeping Genymotion screen idles the render
# pipeline and produces both spurious Compose-test failures AND a black video
# (mirrors run-genymotion-challenges.sh:110-111; forensic anchor there).
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true

export ANDROID_SERIAL="$SERIAL"
export LAVA_REAL_DEVICE_SERIALS="$SERIAL"

# ---------------------------------------------------------------------------
# 2. Start screen recording (delegated to the §11.4.128 recorder).
#    --screenrecord caps at 180s per adb; a Challenge longer than that needs
#    the segmented path (docs/qa/video-challenge-recording.md). For a single
#    Challenge that is well within the cap.
# ---------------------------------------------------------------------------
REC_STATE_HASH="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)"
REC_DEVICE_TOKEN="${DEVICE:-genymotion}"
echo "==> starting screen recording on $SERIAL"
"$RECORDER" start \
  --serial "$SERIAL" \
  --device "$REC_DEVICE_TOKEN" \
  --state-hash "$REC_STATE_HASH" \
  --screenrecord \
  | tee "$EVIDENCE_DIR/recorder-start.log"

# Compute where the recorder placed this recording so we can pull the mp4 later.
REC_DIR="$("$RECORDER" path \
  --serial "$SERIAL" \
  --device "$REC_DEVICE_TOKEN" \
  --state-hash "$REC_STATE_HASH" | head -1)"
# `path` prints the NEXT index; the just-started `start` used the prior index.
# Resolve the actual just-created dir by listing the device dir's newest child.
REC_PARENT="$(dirname "$REC_DIR")"
ACTUAL_REC_DIR="$(ls -d "$REC_PARENT"/recording_* 2>/dev/null | sort | tail -1 || true)"
[[ -n "$ACTUAL_REC_DIR" ]] || ACTUAL_REC_DIR="$REC_DIR"
echo "==> recording dir: $ACTUAL_REC_DIR"

# Give screenrecord a moment to actually begin capturing before the test drives
# the UI (otherwise the first frames — app launch — can be dropped).
sleep 3

# ---------------------------------------------------------------------------
# 3. Run the SINGLE Challenge via connectedDebugAndroidTest.
# ---------------------------------------------------------------------------
GRADLE_TASK=":$MODULE:connectedDebugAndroidTest"
GRADLE_ARGS=( "$GRADLE_TASK" --no-daemon --max-workers=2
              -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" )
[[ "$NO_BUILD" == "1" ]] && GRADLE_ARGS+=( -x assembleDebug )

echo "==> running $GRADLE_TASK on $SERIAL (challenge: $TEST_CLASS)"
set +e
( cd "$ROOT" && nice -n 19 ./gradlew "${GRADLE_ARGS[@]}" ) 2>&1 | tee "$EVIDENCE_DIR/connected-test.log"
RC=${PIPESTATUS[0]}
set -e

# ---------------------------------------------------------------------------
# 4. Stop recording + pull/copy the mp4 into the evidence dir.
# ---------------------------------------------------------------------------
echo "==> stopping screen recording"
"$RECORDER" stop \
  --serial "$SERIAL" \
  --device "$REC_DEVICE_TOKEN" \
  --state-hash "$REC_STATE_HASH" \
  | tee "$EVIDENCE_DIR/recorder-stop.log" || true

# Give the backgrounded screenrecord+pull a moment to flush + pull screen.mp4.
sleep 4

VIDEO_SRC=""
if [[ -f "$ACTUAL_REC_DIR/screen.mp4" ]]; then
  VIDEO_SRC="$ACTUAL_REC_DIR/screen.mp4"
fi

OUT_MP4="$EVIDENCE_DIR/$SAFE_NAME.mp4"
if [[ -n "$VIDEO_SRC" ]]; then
  cp "$VIDEO_SRC" "$OUT_MP4"
  echo "==> challenge video: $OUT_MP4"
else
  echo "WARNING: no screen.mp4 was captured at $ACTUAL_REC_DIR (recorder log: $ACTUAL_REC_DIR/screenrecord.log)." >&2
fi

# ---------------------------------------------------------------------------
# 5. Verdict (§11.4.69 / §6.J): PASS only on gradle exit 0 + verbatim
#    BUILD SUCCESSFUL in the captured log.
# ---------------------------------------------------------------------------
{
  echo "# Challenge video run — $TS"
  echo "device: $SERIAL"
  echo "module: $MODULE  challenge: $TEST_CLASS"
  echo "output-name: $SAFE_NAME"
  echo "gradle exit: $RC"
  grep -aE "BUILD SUCCESSFUL|BUILD FAILED|Tests on|tests completed|FAILED" "$EVIDENCE_DIR/connected-test.log" | tail -20
} | tee "$EVIDENCE_DIR/verdict.txt"

PASS=0
if [[ "$RC" -eq 0 ]] && grep -qa "BUILD SUCCESSFUL" "$EVIDENCE_DIR/connected-test.log"; then
  PASS=1
fi

# ---------------------------------------------------------------------------
# 6. Deliver the SUCCESS video to $HOME and $HOME/Downloads — ON PASS ONLY.
#    A delivered video is itself proof the Challenge passed: FAIL never copies.
# ---------------------------------------------------------------------------
if [[ "$PASS" -eq 1 ]]; then
  if [[ -z "${HOME:-}" ]]; then
    echo "ERROR: \$HOME is unset; cannot deliver the success video." >&2
    exit 2
  fi
  if [[ ! -f "$OUT_MP4" ]]; then
    echo "ERROR: Challenge PASSED but no video was captured — refusing to claim a delivered video that does not exist (§6.J)." >&2
    echo "       Inspect the recorder log: $ACTUAL_REC_DIR/screenrecord.log" >&2
    exit 1
  fi
  DOWNLOADS="$HOME/Downloads"
  mkdir -p "$DOWNLOADS"
  cp "$OUT_MP4" "$HOME/$SAFE_NAME.mp4"
  cp "$OUT_MP4" "$DOWNLOADS/$SAFE_NAME.mp4"
  echo "==> CHALLENGE PASS — success video delivered:"
  echo "      $HOME/$SAFE_NAME.mp4"
  echo "      $DOWNLOADS/$SAFE_NAME.mp4"
  echo "    (evidence: $EVIDENCE_DIR)"
  exit 0
fi

echo "==> CHALLENGE FAIL — video NOT delivered (only success videos reach \$HOME)." >&2
echo "    evidence: $EVIDENCE_DIR" >&2
exit 1
