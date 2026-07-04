#!/usr/bin/env bash
# scripts/autonomous-qa/record-qa-session.sh
# ---------------------------------------------------------------------------
# Records the Android emulator screen during an autonomous QA run and saves
# a verified MP4 to ~/Downloads/lava-qa-<timestamp>-<scenario>.mp4.
#
# The recorder uses `adb shell screenrecord` in the background. Because
# screenrecord is capped at 180 seconds per invocation, long tests are
# recorded as sequential chunks that are concatenated with ffmpeg after the
# test command exits.
#
# Anti-bluff (§6.J / §6.Z): the script refuses to claim success unless the
# recorded file is non-empty AND ffmpeg confirms it contains a video stream
# with at least one decoded frame.
#
# Usage:
#   record-qa-session.sh --serial <serial> --scenario <name> [options]
#
# Required:
#   --serial <serial>     adb serial of the running emulator/device
#   --scenario <name>     short scenario slug for the filename
#
# Optional:
#   --test-command <cmd>  shell command to run while recording (default: sleep 5)
#   --output-dir <dir>    directory for the final MP4 (default: ~/Downloads)
#   --bit-rate <bps>      screenrecord bit-rate (default: 4000000)
#   --chunk-sec <secs>    screenrecord chunk length, max 180 (default: 180)
#   -h|--help             show this help
#
# Exit codes:
#   0 = test command passed and video verified
#   1 = test command failed but video was verified
#   2 = setup error or video verification failed
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
FFMPEG="${FFMPEG:-$(command -v ffmpeg 2>/dev/null || true)}"

SERIAL=""; SCENARIO=""; TEST_CMD="sleep 5"
OUTPUT_DIR="$HOME/Downloads"; BIT_RATE=4000000; CHUNK_SEC=180

usage() {
  sed -n '2,/^# -\{75\}/p' "$0" | sed 's/^# \{0,1\}//;/^# -\{75\}/d'
}

die() { echo "record-qa-session: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)       SERIAL="$2"; shift 2;;
    --scenario)     SCENARIO="$2"; shift 2;;
    --test-command) TEST_CMD="$2"; shift 2;;
    --output-dir)   OUTPUT_DIR="$2"; shift 2;;
    --bit-rate)     BIT_RATE="$2"; shift 2;;
    --chunk-sec)    CHUNK_SEC="$2"; shift 2;;
    -h|--help)      usage; exit 0;;
    *)              usage >&2; die "unknown argument: $1";;
  esac
done

[[ -n "$SERIAL" ]] || { usage >&2; die "--serial is required"; }
[[ -n "$SCENARIO" ]] || { usage >&2; die "--scenario is required"; }
[[ "$CHUNK_SEC" -le 180 ]] || die "--chunk-sec must be <= 180 (screenrecord limit)"
[[ -x "$ADB" ]] || die "adb not found at $ADB (set ANDROID_HOME)"
[[ -n "$FFMPEG" && -x "$FFMPEG" ]] || die "ffmpeg not found on PATH (set \$FFMPEG)"

# Normalize scenario for filename: lowercase, alphanumerics + hyphen only.
SCENARIO_SAFE="$(printf '%s' "$SCENARIO" | tr '[:upper:]' '[:lower:]' | tr -cs 'A-Za-z0-9' '-')"
[[ "$SCENARIO_SAFE" == "-" || -z "$SCENARIO_SAFE" ]] && SCENARIO_SAFE="scenario"

TS="$(date -u +%Y%m%d-%H%M%S)"
OUT_FILE="$OUTPUT_DIR/lava-qa-${TS}-${SCENARIO_SAFE}.mp4"
mkdir -p "$OUTPUT_DIR"

# Temporary workspace for chunks (under /tmp so large captures do not bloat the repo).
WORK="$(mktemp -d /tmp/lava-qa-record-XXXXXX)"
CHUNKS_DIR="$WORK/chunks"
LIST_FILE="$WORK/chunks.txt"
mkdir -p "$CHUNKS_DIR"

echo "[record] serial=$SERIAL scenario=$SCENARIO_SAFE output=$OUT_FILE" >&2

# Verify adb can reach the device before starting.
if ! "$ADB" -s "$SERIAL" shell echo ok >/dev/null 2>&1; then
  die "adb cannot reach serial $SERIAL"
fi

# --- Start chunked screenrecord in the background -----------------------------
# The loop runs screenrecord for CHUNK_SEC seconds, pulls the file, then starts
# the next chunk. It exits when screenrecord fails (e.g. device disconnected) or
# when the parent kills the subshell on cleanup.
(
  i=0
  while :; do
    local_path="$CHUNKS_DIR/chunk_$(printf '%04d' $i).mp4"
    device_path="/sdcard/lava-qa-chunk-$(printf '%04d' $i).mp4"
    # shellcheck disable=SC2068
    if ! "$ADB" -s "$SERIAL" shell screenrecord --bit-rate "$BIT_RATE" --time-limit "$CHUNK_SEC" "$device_path" >/dev/null 2>&1; then
      break
    fi
    # Pull may race if screenrecord is still finalizing; retry briefly.
    pulled=0
    for _ in 1 2 3; do
      if "$ADB" -s "$SERIAL" pull "$device_path" "$local_path" >/dev/null 2>&1; then
        pulled=1
        break
      fi
      sleep 0.5
    done
    "$ADB" -s "$SERIAL" shell rm -f "$device_path" >/dev/null 2>&1 || true
    [[ "$pulled" -eq 1 && -s "$local_path" ]] || break
    i=$((i+1))
  done
) &
REC_PID=$!
echo $REC_PID > "$WORK/.recorder.pid"

# --- Stop recording -----------------------------------------------------------
stop_recording() {
  local pid=""
  [[ -f "$WORK/.recorder.pid" ]] && pid="$(cat "$WORK/.recorder.pid" 2>/dev/null || true)"
  [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  # Send SIGINT to the currently running screenrecord on the device so it
  # finalizes the MP4 cleanly. This is device-global but the emulator is
  # dedicated to this QA run.
  "$ADB" -s "$SERIAL" shell pkill -INT screenrecord >/dev/null 2>&1 || true
  sleep 2
  # Pull any leftover chunks that may still be on the device.
  for f in $("$ADB" -s "$SERIAL" shell ls /sdcard/lava-qa-chunk-*.mp4 2>/dev/null | tr -d '\r' || true); do
    idx="$(printf '%s' "$f" | sed -n 's|.*/lava-qa-chunk-\([0-9]*\)\.mp4|\1|p')"
    local_path="$CHUNKS_DIR/chunk_$(printf '%04d' "${idx:-9999}").mp4"
    "$ADB" -s "$SERIAL" pull "$f" "$local_path" >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell rm -f "$f" >/dev/null 2>&1 || true
  done
  rm -f "$WORK/.recorder.pid"
}

# Ensure cleanup even if the test command is interrupted.
cleanup() {
  stop_recording
}
trap cleanup EXIT

# --- Run the test command -----------------------------------------------------
echo "[record] running test command: $TEST_CMD" >&2
set +e
bash -c "$TEST_CMD"
TEST_RC=$?
set -e
echo "[record] test command exited with rc=$TEST_RC" >&2

stop_recording
trap - EXIT

# --- Build chunk list and concatenate -----------------------------------------
chunk_count=0
: > "$LIST_FILE"
for chunk in "$CHUNKS_DIR"/chunk_*.mp4; do
  [[ -e "$chunk" ]] || continue
  [[ -s "$chunk" ]] || continue
  printf "file '%s'\n" "$chunk" >> "$LIST_FILE"
  chunk_count=$((chunk_count+1))
done

if [[ "$chunk_count" -eq 0 ]]; then
  die "no video chunks were recorded"
fi

if [[ "$chunk_count" -eq 1 ]]; then
  cp "$CHUNKS_DIR"/chunk_*.mp4 "$OUT_FILE"
else
  "$FFMPEG" -hide_banner -y -f concat -safe 0 -i "$LIST_FILE" -c copy "$OUT_FILE" >/dev/null 2>&1
fi

# --- Verify the output file ---------------------------------------------------
if [[ ! -s "$OUT_FILE" ]]; then
  die "output file is missing or empty: $OUT_FILE"
fi

# Probe with ffmpeg -f null -; capture stderr which contains stream info.
probe_out="$("$FFMPEG" -hide_banner -i "$OUT_FILE" -f null - 2>&1 || true)"
if ! printf '%s\n' "$probe_out" | grep -qE 'Stream .* Video:'; then
  {
    echo "record-qa-session: video verification FAILED — no video stream found in $OUT_FILE"
    echo "ffmpeg probe output:"
    printf '%s\n' "$probe_out"
  } >&2
  exit 2
fi

# Parse the decoded frame count from the ffmpeg summary line.
frame_count="$(printf '%s\n' "$probe_out" | grep -oE 'frame=[[:space:]]*[0-9]+' | tail -1 | grep -oE '[0-9]+' || echo 0)"
if [[ "$frame_count" -lt 1 ]]; then
  die "video verification FAILED — output contains a video stream but 0 decoded frames"
fi

# Optional metadata for caller logs.
resolution="$(printf '%s\n' "$probe_out" | grep -oE '[0-9]+x[0-9]+' | head -1 || echo unknown)"
file_size="$(stat -c%s "$OUT_FILE" 2>/dev/null || stat -f%z "$OUT_FILE" 2>/dev/null || echo 0)"
echo "[record] saved $OUT_FILE (size=$file_size bytes, chunks=$chunk_count, frames=$frame_count, resolution=$resolution)" >&2

# Cleanup workspace on success.
rm -rf "$WORK"

if [[ "$TEST_RC" -eq 0 ]]; then
  exit 0
else
  exit 1
fi
