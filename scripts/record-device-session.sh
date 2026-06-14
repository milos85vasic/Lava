#!/usr/bin/env bash
# scripts/record-device-session.sh — §11.4.128 always-on device-recorder (LVA-side).
#
# CONTRACT (CLAUDE.md §6.AI / §6.AI-debt / HelixConstitution §11.4.128):
#   A background, side-effect-free, subagent-driven recorder (logcat / perf /
#   crash-ANR + optional screenrecord/screenshot) writing raw output into the
#   deterministic layout:
#
#       <root>/YYYY-MM-DD/<state-hash>/<DEVICE>_<SERIAL>/recording_NNN/
#
#   - Raw output is git-ignored + codegraph-excluded; only CURATED evidence is
#     ever committed (operator/release-prep promotes a subset out of <root>).
#   - Default posture is CAPTURE-AND-STORE; analysis happens only at release-prep
#     or on explicit operator request — this script does NOT analyse.
#   - Capture is READ-ONLY w.r.t. the app under test: `adb logcat`,
#     `adb shell screenrecord`, `adb exec-out screencap` are all read-only
#     observers; nothing here installs, clears, force-stops, or writes app state.
#     (Contrast: `pm clear` / `am force-stop` are FORBIDDEN here.)
#
# DEVICE SERIAL RESOLUTION (mirrors run-genymotion-challenges.sh convention):
#   1) positional/`--serial <s>` arg, else
#   2) first token of $LAVA_REAL_DEVICE_SERIALS, else
#   3) error (exit 2) — we never guess a serial.
#   The serial is expected to belong to a Containers-submodule-driven Genymotion
#   VM or container emulator (§6.AG/§6.AH: never a host-direct emulator, never a
#   live ADB device borrowed from another project).
#
# Usage:
#   scripts/record-device-session.sh start [--serial <s>] [--device <name>] \
#       [--root <dir>] [--state-hash <h>] [--screenrecord] [--screenshot-interval <sec>]
#   scripts/record-device-session.sh stop  [--serial <s>] [--root <dir>] [--state-hash <h>] [--device <name>]
#   scripts/record-device-session.sh path  [--serial <s>] [--device <name>] [--root <dir>] [--state-hash <h>]
#       # prints the next recording_NNN dir WITHOUT creating it (dry-run)
#
# Exit: 0 ok; 2 config/usage/serial error; 1 capture-start failure.
#
# UNIT-TESTABILITY: when sourced (BASH_SOURCE[0] != $0) this file defines its
# pure functions and returns WITHOUT running any command — see the guard at the
# bottom. tests/device-recording/test_record_path.sh exercises the pure path
# math with NO device attached. The device-bound capture itself is
# UNVERIFIED-WITHOUT-A-DEVICE (no live device available to this agent).
set -euo pipefail

# ---------------------------------------------------------------------------
# Pure helpers (no side effects; safe to source).
# ---------------------------------------------------------------------------

# rds_default_root <repo-root> -> the default gitignored raw-recordings root.
rds_default_root() {
  printf '%s/.lava-ci-evidence/device-recordings' "$1"
}

# rds_today -> UTC date stamp YYYY-MM-DD. Overridable via $RDS_FAKE_DATE for
# hermetic testing (no clock dependency in the unit test).
rds_today() {
  if [[ -n "${RDS_FAKE_DATE:-}" ]]; then
    printf '%s' "$RDS_FAKE_DATE"
  else
    date -u +%Y-%m-%d
  fi
}

# rds_sanitize <raw> -> filesystem-safe token: keep [A-Za-z0-9._-], collapse the
# rest to '-'. Deterministic; used for both DEVICE and SERIAL path components so
# an adb serial like "127.0.0.1:6555" or "R5CW33CBVQV" yields a stable dir name.
rds_sanitize() {
  local s="$1"
  s="${s//[^A-Za-z0-9._-]/-}"
  printf '%s' "$s"
}

# rds_state_hash <repo-root> -> a short, deterministic <state-hash> for the
# current working-tree state. Prefers `git rev-parse --short HEAD`; falls back to
# "nogit" when git is unavailable. Overridable via $RDS_STATE_HASH so callers (and
# the unit test) can pin it. This groups all recordings taken against one code
# state under one directory, per §11.4.128's deterministic layout.
rds_state_hash() {
  if [[ -n "${RDS_STATE_HASH:-}" ]]; then
    rds_sanitize "$RDS_STATE_HASH"; return 0
  fi
  local h
  if h="$(git -C "$1" rev-parse --short HEAD 2>/dev/null)"; then
    rds_sanitize "$h"
  else
    printf 'nogit'
  fi
}

# rds_device_dir <root> <date> <state-hash> <device> <serial>
#   -> "<root>/<date>/<state-hash>/<DEVICE>_<SERIAL>"  (the per-device dir;
#      the recording_NNN child is computed separately so NNN can increment).
rds_device_dir() {
  local root="$1" date="$2" hash="$3" device="$4" serial="$5"
  printf '%s/%s/%s/%s_%s' \
    "$root" "$date" "$(rds_sanitize "$hash")" \
    "$(rds_sanitize "$device")" "$(rds_sanitize "$serial")"
}

# rds_next_index <device-dir> -> the next zero-padded recording index (NNN).
# Scans existing recording_NNN children; returns max+1, or 1 when none exist.
# Pure w.r.t. the app; only reads the recordings tree. Idempotent: calling it
# twice without creating a dir returns the same value.
rds_next_index() {
  local device_dir="$1" max=0 n
  if [[ -d "$device_dir" ]]; then
    local d
    for d in "$device_dir"/recording_*; do
      [[ -d "$d" ]] || continue
      n="${d##*/recording_}"
      # strip leading zeros safely; non-numeric -> skip
      [[ "$n" =~ ^[0-9]+$ ]] || continue
      n=$((10#$n))
      (( n > max )) && max="$n"
    done
  fi
  printf '%03d' "$(( max + 1 ))"
}

# rds_recording_dir <root> <date> <state-hash> <device> <serial>
#   -> the full next "<...>/recording_NNN" path (does NOT create it).
rds_recording_dir() {
  local device_dir; device_dir="$(rds_device_dir "$1" "$2" "$3" "$4" "$5")"
  printf '%s/recording_%s' "$device_dir" "$(rds_next_index "$device_dir")"
}

# ---------------------------------------------------------------------------
# Side-effecting driver (only runs when executed, never when sourced).
# ---------------------------------------------------------------------------

rds_resolve_serial() {
  # $1 = explicit serial arg (may be empty). Falls back to first token of
  # LAVA_REAL_DEVICE_SERIALS. Never guesses.
  local explicit="$1"
  if [[ -n "$explicit" ]]; then printf '%s' "$explicit"; return 0; fi
  local first
  first="$(printf '%s' "${LAVA_REAL_DEVICE_SERIALS:-}" | awk '{print $1}')"
  if [[ -n "$first" ]]; then printf '%s' "$first"; return 0; fi
  return 1
}

rds_usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" >&2
}

rds_main() {
  local cmd="${1:-}"; shift || true
  local SERIAL_ARG="" DEVICE="device" ROOT="" STATE_HASH_ARG="" SCREENREC=0 SHOT_INTERVAL=0
  local REPO_ROOT; REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --serial) SERIAL_ARG="$2"; shift 2 ;;
      --device) DEVICE="$2"; shift 2 ;;
      --root) ROOT="$2"; shift 2 ;;
      --state-hash) STATE_HASH_ARG="$2"; shift 2 ;;
      --screenrecord) SCREENREC=1; shift ;;
      --screenshot-interval) SHOT_INTERVAL="$2"; shift 2 ;;
      *) echo "unknown arg: $1" >&2; rds_usage; exit 2 ;;
    esac
  done

  [[ -n "$ROOT" ]] || ROOT="$(rds_default_root "$REPO_ROOT")"
  [[ -n "$STATE_HASH_ARG" ]] && export RDS_STATE_HASH="$STATE_HASH_ARG"
  local DATE; DATE="$(rds_today)"
  local HASH; HASH="$(rds_state_hash "$REPO_ROOT")"

  local SERIAL=""
  if [[ "$cmd" != "" ]]; then
    SERIAL="$(rds_resolve_serial "$SERIAL_ARG")" || {
      echo "ERROR: no device serial. Pass --serial <s> or set LAVA_REAL_DEVICE_SERIALS." >&2
      exit 2
    }
  fi

  case "$cmd" in
    path)
      # Dry-run: compute + print the next recording dir, create nothing.
      rds_recording_dir "$ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL"
      echo
      ;;
    start)
      command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not on PATH." >&2; exit 2; }
      local REC_DIR; REC_DIR="$(rds_recording_dir "$ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
      mkdir -p "$REC_DIR"
      echo "==> recording dir: $REC_DIR"
      # Capture device identity (read-only).
      {
        echo "serial=$SERIAL"
        echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | sed 's/^/model=/'
        adb -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' | sed 's/^/android_release=/'
        adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' | sed 's/^/sdk=/'
      } > "$REC_DIR/device-identity.txt" || true

      # Background logcat (read-only observer). -v threadtime gives crash/ANR
      # context; we do NOT pass -c (which would CLEAR the buffer = a side effect).
      adb -s "$SERIAL" logcat -v threadtime > "$REC_DIR/logcat.txt" 2>&1 &
      echo "$!" > "$REC_DIR/logcat.pid"
      echo "==> logcat capturing (pid $(cat "$REC_DIR/logcat.pid"))"

      if [[ "$SCREENREC" == "1" ]]; then
        # screenrecord is read-only; caps at 180s per adb. Background + record pid.
        ( adb -s "$SERIAL" shell screenrecord --time-limit 180 /sdcard/lava-rec.mp4 \
            && adb -s "$SERIAL" pull /sdcard/lava-rec.mp4 "$REC_DIR/screen.mp4" ) \
            > "$REC_DIR/screenrecord.log" 2>&1 &
        echo "$!" > "$REC_DIR/screenrecord.pid"
      fi

      if [[ "$SHOT_INTERVAL" -gt 0 ]] 2>/dev/null; then
        ( i=0; while sleep "$SHOT_INTERVAL"; do
            adb -s "$SERIAL" exec-out screencap -p > "$REC_DIR/shot_$(printf '%04d' "$i").png" 2>/dev/null || break
            i=$((i+1))
          done ) &
        echo "$!" > "$REC_DIR/screenshot.pid"
      fi
      echo "==> capture started. Stop with: $0 stop --serial $SERIAL --root $ROOT --state-hash $HASH --device $DEVICE"
      ;;
    stop)
      # Stop the most-recent recording dir for this device/state by reaping pids.
      local device_dir; device_dir="$(rds_device_dir "$ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
      local last
      last="$(ls -d "$device_dir"/recording_* 2>/dev/null | sort | tail -1 || true)"
      if [[ -z "$last" ]]; then
        echo "ERROR: no recording dir under $device_dir to stop." >&2; exit 2
      fi
      local pidf
      for pidf in "$last"/logcat.pid "$last"/screenrecord.pid "$last"/screenshot.pid; do
        [[ -f "$pidf" ]] || continue
        local pid; pid="$(cat "$pidf")"
        if [[ "$pidf" == *screenrecord.pid ]]; then
          # `adb shell screenrecord` finalizes the .mp4 container ONLY when its
          # DEVICE-SIDE process receives SIGINT (graceful) — killing the LOCAL
          # adb wrapper truncates the file AND skips the wrapper's `&& adb pull`,
          # which was the "Challenge PASSED but no video" defect (2026-06-14).
          # SIGINT the device-side process so it finalizes + exits 0, then let the
          # wrapper subshell complete its pull (bounded wait) before reaping.
          adb -s "$SERIAL" shell pkill -INT screenrecord >/dev/null 2>&1 || true
          local waited=0
          while kill -0 "$pid" 2>/dev/null && [[ "$waited" -lt 20 ]]; do
            sleep 1; waited=$((waited + 1))
          done
        fi
        if kill "$pid" 2>/dev/null; then echo "==> stopped $(basename "$pidf") (pid $pid)"; fi
        rm -f "$pidf"
      done
      echo "stopped_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$last/device-identity.txt" 2>/dev/null || true
      echo "==> stopped recording: $last"
      ;;
    ""|-h|--help|help)
      rds_usage; [[ "$cmd" == "" ]] && exit 2 || exit 0
      ;;
    *)
      echo "unknown command: $cmd" >&2; rds_usage; exit 2 ;;
  esac
}

# Guard: only run when EXECUTED, not when SOURCED (unit-test sources this file
# to test the pure functions with no device + no side effects).
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  rds_main "$@"
fi
