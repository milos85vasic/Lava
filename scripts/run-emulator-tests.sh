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
TEST_REPORT_GLOB="$DEFAULT_TEST_REPORT_GLOB"
IMAGE_MANIFEST=""

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
        --test-report-glob) TEST_REPORT_GLOB="$2"; shift 2 ;;
        --image-manifest) IMAGE_MANIFEST="$2"; shift 2 ;;
        --no-build) BUILD_APK=0; shift ;;
        --help|-h)
            cat <<USAGE
Usage: $0 [--test-class <fqcn>] [--avds <list>] [--evidence-dir <path>]
          [--tag <tag>] [--boot-timeout <duration>] [--concurrent N] [--dev]
          [--test-report-glob <glob>] [--image-manifest <path>] [--no-build]

Defaults:
  --test-class        $DEFAULT_TEST_CLASS
  --avds              $DEFAULT_AVDS
  --evidence-dir      auto: .lava-ci-evidence/Lava-Android-<v>-<vc>/matrix/<UTC>/
  --tag               (overrides the auto-detected Lava-Android-<v>-<vc> prefix)
  --boot-timeout      5m (forwarded to cmd/emulator-matrix; e.g. 10m, 600s)
  --concurrent        1 (serial; >1 sets gating=false in the attestation)
  --dev               false (set true to permit snapshot reload; sets gating=false)
  --test-report-glob  $DEFAULT_TEST_REPORT_GLOB
  --image-manifest    "" (empty preserves pre-Phase-2 behavior; set to a
                      vm-images.json path to opt in to pkg/cache routing
                      for missing Android system-images, e.g.
                      tools/lava-containers/vm-images.json)

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

"$BIN_DIR/emulator-matrix" \
    --android-sdk-root "$ANDROID_SDK_ROOT" \
    --apk "$APK_PATH" \
    --test-class "$TEST_CLASS" \
    --evidence-dir "$EVIDENCE_DIR" \
    --avds "$AVDS" \
    --cold-boot \
    "${extra_args[@]}"
