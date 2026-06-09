#!/usr/bin/env bash
# tests/device-recording/test_record_path.sh — hermetic falsifiability test for
# the §11.4.128 device-recorder's deterministic path math.
#
# WHAT THIS PROVES (no live device required):
#   1. The path layout is EXACTLY <root>/YYYY-MM-DD/<state-hash>/<DEVICE>_<SERIAL>/recording_NNN/
#   2. NNN starts at 001 and increments to 002, 003 as recording dirs appear.
#   3. The computation is IDEMPOTENT: computing the "next" dir twice without
#      creating anything returns the same path (no hidden mutation).
#   4. adb-serial-shaped strings ("127.0.0.1:6555") are sanitized into a stable
#      single path component.
#
# It SOURCES scripts/record-device-session.sh (the source-guard means no command
# runs) and calls the pure functions directly. This is the part of §11.4.128 that
# is verifiable WITHOUT a device; the device-bound capture is UNVERIFIED here.
#
# Exit 0 = all assertions hold.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/record-device-session.sh"

# shellcheck source=/dev/null
source "$SCRIPT"

fail() { echo "FAIL: $*" >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REC_ROOT="$TMP/recs"
DATE="2026-06-09"
HASH="abc1234"
DEVICE="Google Pixel 9"
SERIAL="127.0.0.1:6555"

# Expected sanitized components.
EXP_DEVICE_DIR="$REC_ROOT/$DATE/$HASH/Google-Pixel-9_127.0.0.1-6555"

echo "test: §11.4.128 deterministic path layout"

# --- 1. device-dir layout ---------------------------------------------------
got_devdir="$(rds_device_dir "$REC_ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
[[ "$got_devdir" == "$EXP_DEVICE_DIR" ]] \
  || fail "device-dir mismatch. want [$EXP_DEVICE_DIR] got [$got_devdir]"
echo "  device-dir layout: PASS"

# --- 2. first index is 001, full recording path ----------------------------
got1="$(rds_recording_dir "$REC_ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
exp1="$EXP_DEVICE_DIR/recording_001"
[[ "$got1" == "$exp1" ]] || fail "first recording dir. want [$exp1] got [$got1]"
echo "  first index = 001: PASS"

# --- 3. IDEMPOTENT: computing again without creating returns the SAME path --
got1b="$(rds_recording_dir "$REC_ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
[[ "$got1b" == "$exp1" ]] || fail "not idempotent. want [$exp1] got [$got1b]"
echo "  idempotent (no creation -> same NNN): PASS"

# --- 4. NNN increments as recording dirs appear ----------------------------
mkdir -p "$EXP_DEVICE_DIR/recording_001"
got2="$(rds_recording_dir "$REC_ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
exp2="$EXP_DEVICE_DIR/recording_002"
[[ "$got2" == "$exp2" ]] || fail "after 001 exists, want [$exp2] got [$got2]"

mkdir -p "$EXP_DEVICE_DIR/recording_002"
got3="$(rds_recording_dir "$REC_ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
exp3="$EXP_DEVICE_DIR/recording_003"
[[ "$got3" == "$exp3" ]] || fail "after 002 exists, want [$exp3] got [$got3]"
echo "  NNN increments 001->002->003: PASS"

# --- 5. gap-tolerance: max+1 even with a gap (002 missing) ------------------
rm -rf "$EXP_DEVICE_DIR/recording_002"
mkdir -p "$EXP_DEVICE_DIR/recording_005"
got_gap="$(rds_recording_dir "$REC_ROOT" "$DATE" "$HASH" "$DEVICE" "$SERIAL")"
exp_gap="$EXP_DEVICE_DIR/recording_006"
[[ "$got_gap" == "$exp_gap" ]] || fail "gap handling, want [$exp_gap] got [$got_gap]"
echo "  max+1 with gap (005 -> 006): PASS"

# --- 6. RDS_FAKE_DATE wiring (clock-independent today) ----------------------
RDS_FAKE_DATE="2099-12-31"
[[ "$(rds_today)" == "2099-12-31" ]] || fail "RDS_FAKE_DATE override broken"
unset RDS_FAKE_DATE
echo "  date override: PASS"

echo "ALL PASS — §11.4.128 path layout verified (no device required)"
