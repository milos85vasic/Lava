#!/bin/bash
# scripts/run-emulator-tests.sh — Lava-side thin glue around the
# Containers submodule's cmd/emulator-matrix CLI (Phase 3.2 of the
# 2026-05-04 pending-completion plan; closes the constitutional 6.K-debt
# entry in CLAUDE.md).
#
# Per clauses 6.I (Multi-Emulator Container Matrix) + 6.K (Builds-
# Inside-Containers Mandate) + the Decoupled Reusable Architecture
# rule: the matrix-orchestration capability lives in
# `vasic-digital/Containers/pkg/emulator/`. This script:
#
#   1. Builds the cmd/emulator-matrix binary from the pinned submodule
#      (./Submodules/Containers).
#   2. Builds the Lava debug APK if requested.
#   3. Invokes the binary with Lava-specific arguments: AVD list, test
#      class, evidence directory.
#
# All matrix logic — boot, install, run, teardown, attestation file
# writing — is owned by the Containers submodule. New AVD form factors,
# QEMU support, and other-OS emulators are extension points there, NOT
# here.
#
# Usage:
#
#   ./scripts/run-emulator-tests.sh \
#       [--test-class lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest] \
#       [--avds CZ_API28_Phone:28:phone,CZ_API30_Phone:30:phone,CZ_API34_Phone:34:phone,Pixel_9a:36:phone] \
#       [--evidence-dir .lava-ci-evidence/2026-05-04-matrix] \
#       [--image-manifest tools/lava-containers/vm-images.json] \
#       [--no-build]
#
# Environment:
#   ANDROID_SDK_ROOT or ANDROID_HOME — points to the Android SDK
#
# Exit codes:
#   0  matrix passed (every AVD booted, every test passed)
#   1  matrix failed (at least one AVD failed)
#   2  configuration error (missing flags, missing SDK, etc.)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# Defaults
DEFAULT_TEST_CLASS="lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest"
DEFAULT_AVDS="CZ_API28_Phone:28:phone,CZ_API30_Phone:30:phone,CZ_API34_Phone:34:phone,CZ_API34_Tablet:34:tablet,CZ_API35_Phone:35:phone"
DEFAULT_TEST_REPORT_GLOB="app/build/outputs/androidTest-results/connected/debug/TEST-*.xml"

TEST_CLASS="$DEFAULT_TEST_CLASS"
AVDS="$DEFAULT_AVDS"
EVIDENCE_DIR=""           # set by detect_evidence_dir below
TAG_OVERRIDE=""
BUILD_APK=1
BOOT_TIMEOUT=""
CONCURRENT=""
DEV_MODE=0
# §6.X Container-Submodule Emulator Wiring Mandate (added 2026-05-13):
# the gate run MUST execute the emulator process INSIDE a podman/docker
# container managed by Submodules/Containers/. Until §6.X-debt closes
# (Containers-side `Containerized` Emulator implementation), `host-direct`
# remains the default and is permitted ONLY for workstation iteration.
# `--runner=containerized` is the forward-looking gate target — when
# Containers ships the wiring, `scripts/tag.sh` will reject attestation
# rows without it. Defaults to `host-direct` per the transitional clause.
RUNNER="host-direct"
TEST_REPORT_GLOB="$DEFAULT_TEST_REPORT_GLOB"
IMAGE_MANIFEST=""
# Phase 6 (Group C remaining) — per-row network simulation +
# screenshot-on-failure capture. Empty values = use cmd/emulator-matrix
# defaults (no shaping; capture on failure enabled).
NETWORK_PROFILE=""
NETWORK_BANDWIDTH_DOWN=""
NETWORK_BANDWIDTH_UP=""
NETWORK_LATENCY=""
NETWORK_LOSS=""
CAPTURE_SCREENSHOT_FLAG=""

# detect_version_prefix returns "Lava-Android-<versionName>-<versionCode>"
# parsed from app/build.gradle.kts. Echoes "Lava-Android-unknown" when
# the file is missing or unparseable so an iteration run still proceeds
# (tag.sh's gates will reject the resulting evidence anyway).
#
# Lava's app/build.gradle.kts uses `versionName = "X.Y.Z"` /
# `versionCode = N` (with `=`); the regex matches that shape.
detect_version_prefix() {
    local f="$PROJECT_DIR/app/build.gradle.kts"
    if [[ ! -f "$f" ]]; then
        echo "Lava-Android-unknown"
        return 0
    fi
    local v vc
    v=$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' "$f" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
    vc=$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$f" | head -n1 | grep -oE '[0-9]+$')
    if [[ -z "$v" || -z "$vc" ]]; then
        echo "Lava-Android-unknown"
        return 0
    fi
    echo "Lava-Android-${v}-${vc}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --test-class) TEST_CLASS="$2"; shift 2 ;;
        --avds) AVDS="$2"; shift 2 ;;
        --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
        --tag) TAG_OVERRIDE="$2"; shift 2 ;;
        --boot-timeout) BOOT_TIMEOUT="$2"; shift 2 ;;
        --concurrent) CONCURRENT="$2"; shift 2 ;;
        --dev) DEV_MODE=1; shift ;;
        --runner)
            RUNNER="$2"
            case "$RUNNER" in
                host-direct|containerized) ;;
                *) echo "ERROR: --runner must be host-direct|containerized (got: $RUNNER)" >&2; exit 2 ;;
            esac
            shift 2
            ;;
        --test-report-glob) TEST_REPORT_GLOB="$2"; shift 2 ;;
        --image-manifest) IMAGE_MANIFEST="$2"; shift 2 ;;
        --network-profile) NETWORK_PROFILE="$2"; shift 2 ;;
        --network-bandwidth-down) NETWORK_BANDWIDTH_DOWN="$2"; shift 2 ;;
        --network-bandwidth-up) NETWORK_BANDWIDTH_UP="$2"; shift 2 ;;
        --network-latency) NETWORK_LATENCY="$2"; shift 2 ;;
        --network-loss) NETWORK_LOSS="$2"; shift 2 ;;
        --capture-screenshot-on-failure) CAPTURE_SCREENSHOT_FLAG="$2"; shift 2 ;;
        --no-build) BUILD_APK=0; shift ;;
        --help|-h)
            cat <<USAGE
Usage: $0 [--test-class <fqcn>] [--avds <list>] [--evidence-dir <path>]
          [--tag <tag>] [--boot-timeout <duration>] [--concurrent N] [--dev]
          [--test-report-glob <glob>] [--image-manifest <path>]
          [--network-profile <name>] [--network-bandwidth-down <kbps>]
          [--network-bandwidth-up <kbps>] [--network-latency <ms>]
          [--network-loss <pct>] [--capture-screenshot-on-failure true|false]
          [--no-build]

Defaults:
  --test-class        $DEFAULT_TEST_CLASS
  --avds              $DEFAULT_AVDS
  --evidence-dir      auto: .lava-ci-evidence/Lava-Android-<v>-<vc>/matrix/<UTC>/
  --tag               (overrides the auto-detected Lava-Android-<v>-<vc> prefix)
  --boot-timeout      5m (forwarded to cmd/emulator-matrix; e.g. 10m, 600s)
  --concurrent        1 (serial; >1 sets gating=false in the attestation)
  --dev               false (set true to permit snapshot reload; sets gating=false)
  --runner            host-direct|containerized (default: host-direct).
                      §6.X Container-Submodule Emulator Wiring Mandate: the
                      gate target is `containerized` (emulator runs INSIDE
                      a podman/docker container managed by Submodules/Containers/).
                      `host-direct` is permitted ONLY for workstation
                      iteration during §6.X-debt; `scripts/tag.sh` will
                      reject attestation rows without `runner: containerized`
                      once the Containers-side wiring lands.
  --test-report-glob  $DEFAULT_TEST_REPORT_GLOB
  --image-manifest    "" (empty preserves pre-Phase-2 behavior; set to a
                      vm-images.json path to opt in to pkg/cache routing
                      for missing Android system-images, e.g.
                      tools/lava-containers/vm-images.json)
  --network-profile   "" (no shaping). Valid: edge|2g|3g|4g|lte|wifi|ethernet|none.
                      Phase 6 (Group C remaining) — per-row network simulation
                      via 'adb emu network speed/delay'.
  --network-bandwidth-{down,up}  0 = use profile default. Override on top
                      of --network-profile. Both in kbps.
  --network-latency   0 = use profile default. Milliseconds.
  --network-loss      0 = use profile default. Packet loss percentage [0,100].
                      (Note: Android emulator console does not honour loss;
                      recorded in the attestation regardless.)
  --capture-screenshot-on-failure  true (default). Forensic 'adb exec-out
                      screencap -p' on a failed row, written to
                      <evidence>/<avd>/screenshot-on-failure.png. Set false
                      to opt out.

Evidence-path resolution priority:
  1. --evidence-dir <path>     (existing flag, wins)
  2. --tag <tag>               (.lava-ci-evidence/<tag>/matrix/<UTC>/)
  3. auto-detect               (.lava-ci-evidence/Lava-Android-<v>-<vc>/matrix/<UTC>/)

The AVD list is comma-separated. Each entry MAY include the API level
and form factor as Name:APILevel:FormFactor. The matrix runner records
those metadata in the per-AVD attestation row.
USAGE
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 2 ;;
    esac
done

# Resolve evidence dir if the operator did not pass --evidence-dir.
if [[ -z "$EVIDENCE_DIR" ]]; then
    if [[ -n "$TAG_OVERRIDE" ]]; then
        EVIDENCE_DIR=".lava-ci-evidence/${TAG_OVERRIDE}/matrix/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
    else
        prefix=$(detect_version_prefix)
        EVIDENCE_DIR=".lava-ci-evidence/${prefix}/matrix/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
    fi
fi

# Pre-flight checks
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]] || [[ ! -d "$ANDROID_SDK_ROOT/emulator" ]]; then
    echo "ERROR: ANDROID_SDK_ROOT or ANDROID_HOME MUST point to a valid Android SDK." >&2
    exit 2
fi
export ANDROID_SDK_ROOT

if [[ ! -e /dev/kvm ]]; then
    echo "ERROR: /dev/kvm not available. Emulator cannot start without hardware virtualization." >&2
    exit 2
fi

CONTAINERS_DIR="$PROJECT_DIR/Submodules/Containers"
if [[ ! -d "$CONTAINERS_DIR/pkg/emulator" ]]; then
    echo "ERROR: Submodules/Containers/pkg/emulator not found." >&2
    echo "  → run \`git submodule update --init Submodules/Containers\` and ensure" >&2
    echo "    the pin includes commit 7614b94 or later (Phase 3.1 of the 2026-05-04 plan)." >&2
    exit 2
fi

# Build the matrix + cleanup binaries from the pinned Containers submodule.
BIN_DIR="$PROJECT_DIR/build/emulator-matrix"
mkdir -p "$BIN_DIR"
echo "[1/3] Building cmd/emulator-matrix + cmd/emulator-cleanup from $CONTAINERS_DIR ..."
( cd "$CONTAINERS_DIR" && go build -o "$BIN_DIR/emulator-matrix" ./cmd/emulator-matrix/ )
( cd "$CONTAINERS_DIR" && go build -o "$BIN_DIR/emulator-cleanup" ./cmd/emulator-cleanup/ )

echo "[0/3] Pre-boot qemu-zombie cleanup (clause 6.M action item, via Containers cmd/emulator-cleanup) ..."
"$BIN_DIR/emulator-cleanup" --verbose 2>&1 || true

# Build the Lava debug APK if requested.
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ "$BUILD_APK" -eq 1 ]]; then
    echo "[2/3] Building Lava debug APK ..."
    ./gradlew :app:assembleDebug --no-daemon
fi
if [[ ! -f "$APK_PATH" ]]; then
    echo "ERROR: APK not found at $APK_PATH" >&2
    exit 2
fi

# Invoke the matrix runner. Per clauses 6.I/6.J: exit code 0 ⇒ matrix
# passed; exit code 1 ⇒ matrix failed; nothing in between.
mkdir -p "$EVIDENCE_DIR"
echo "[3/3] Running matrix:"
echo "  AVDs:        $AVDS"
echo "  Test class:  $TEST_CLASS"
echo "  Evidence:    $EVIDENCE_DIR"
echo

extra_args=()
if [[ -n "$BOOT_TIMEOUT" ]]; then
    extra_args+=(--boot-timeout "$BOOT_TIMEOUT")
fi
if [[ -n "$CONCURRENT" ]]; then
    extra_args+=(--concurrent "$CONCURRENT")
fi
if [[ "$DEV_MODE" -eq 1 ]]; then
    extra_args+=(--dev)
fi
if [[ -n "$TEST_REPORT_GLOB" ]]; then
    extra_args+=(--test-report-glob "$TEST_REPORT_GLOB")
fi
if [[ -n "$IMAGE_MANIFEST" ]]; then
    extra_args+=(--image-manifest "$IMAGE_MANIFEST")
fi
# Phase 6 (Group C remaining) — per-row network simulation +
# screenshot-on-failure capture. Forwarding is mechanical; the
# Containers cmd/emulator-matrix CLI validates ranges and rejects
# bad values with exit code 2.
if [[ -n "$NETWORK_PROFILE" ]]; then
    extra_args+=(--network-profile "$NETWORK_PROFILE")
fi
if [[ -n "$NETWORK_BANDWIDTH_DOWN" ]]; then
    extra_args+=(--network-bandwidth-down "$NETWORK_BANDWIDTH_DOWN")
fi
if [[ -n "$NETWORK_BANDWIDTH_UP" ]]; then
    extra_args+=(--network-bandwidth-up "$NETWORK_BANDWIDTH_UP")
fi
if [[ -n "$NETWORK_LATENCY" ]]; then
    extra_args+=(--network-latency "$NETWORK_LATENCY")
fi
if [[ -n "$NETWORK_LOSS" ]]; then
    extra_args+=(--network-loss "$NETWORK_LOSS")
fi
if [[ -n "$CAPTURE_SCREENSHOT_FLAG" ]]; then
    extra_args+=(--capture-screenshot-on-failure="$CAPTURE_SCREENSHOT_FLAG")
fi

# §6.X Container-Submodule Emulator Wiring Mandate: forward the runner
# choice to the matrix CLI. The CLI accepts unknown flags gracefully
# during the §6.X-debt transition (the flag is informational until the
# Containers-side `Containerized` Emulator implementation lands).
# Recording the runner in attestation rows is the path forward for
# `scripts/tag.sh`'s `runner: containers-submodule` gate.
echo "[§6.X] runner=$RUNNER (host-direct permitted during §6.X-debt; containerized is the gate target)"
# Probe whether the binary accepts --runner: if `--help` mentions it, forward;
# otherwise the flag is informational-only (recorded in this script's stdout
# and in the §6.X-debt note) until Containers-side adds parser support.
if "$BIN_DIR/emulator-matrix" --help 2>&1 | grep -q -- "--runner"; then
    extra_args+=(--runner "$RUNNER")
else
    echo "[§6.X] note: cmd/emulator-matrix does not yet accept --runner; recorded informationally."
fi

"$BIN_DIR/emulator-matrix" \
    --android-sdk-root "$ANDROID_SDK_ROOT" \
    --apk "$APK_PATH" \
    --test-class "$TEST_CLASS" \
    --evidence-dir "$EVIDENCE_DIR" \
    --avds "$AVDS" \
    --cold-boot \
    "${extra_args[@]}"
