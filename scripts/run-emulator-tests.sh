#!/bin/bash
set -euo pipefail

# =============================================================================
# Lava End-to-End Emulator Test Runner
# =============================================================================
# Boots the Android emulator container, waits for it to be ready,
# installs the debug APK, and runs all Challenge Tests (C1–C8)
# plus provider-specific end-to-end tests.
#
# Constitutional requirement: clause 6.G — every provider MUST have at
# least one end-to-end test that exercises the real production stack on
# a real device or emulator.
#
# Usage:
#   ./scripts/run-emulator-tests.sh [--avd=Pixel_9a] [--no-build]
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

AVD_NAME="${AVD_NAME:-Pixel_9a}"
BUILD_APK=1

for arg in "$@"; do
    case "$arg" in
        --avd=*) AVD_NAME="${arg#--avd=}" ;;
        --no-build) BUILD_APK=0 ;;
        *) echo "Unknown option: $arg"; exit 1 ;;
    esac
done

export AVD_NAME

# Verify KVM is available on the host
if [ ! -e /dev/kvm ]; then
    echo "ERROR: /dev/kvm not available. Emulator cannot start without hardware virtualization."
    echo "Enable KVM in BIOS/UEFI and ensure the kvm kernel module is loaded."
    exit 1
fi

# Verify Android SDK is set
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$ANDROID_SDK_ROOT" ] || [ ! -d "$ANDROID_SDK_ROOT/emulator" ]; then
    echo "ERROR: ANDROID_SDK_ROOT or ANDROID_HOME must point to a valid Android SDK."
    echo "Current: ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-(unset)}"
    exit 1
fi

# Verify the AVD exists on the host
if ! "$ANDROID_SDK_ROOT/emulator/emulator" -list-avds | grep -qx "$AVD_NAME"; then
    echo "ERROR: AVD '$AVD_NAME' not found in host SDK."
    echo "Available AVDs:"
    "$ANDROID_SDK_ROOT/emulator/emulator" -list-avds
    echo ""
    echo "Create one with:"
    echo "  $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager create avd -n Pixel_9a -k 'system-images;android-35;google_apis;x86_64' -d pixel_9"
    exit 1
fi

echo "========================================"
echo "  Lava Emulator Test Runner"
echo "  AVD: $AVD_NAME"
echo "  SDK: $ANDROID_SDK_ROOT"
echo "========================================"

# ---------------------------------------------------------------------------
# Step 1: Build the debug APK (unless --no-build)
# ---------------------------------------------------------------------------
if [ "$BUILD_APK" -eq 1 ]; then
    echo ""
    echo "[1/5] Building debug APK..."
    ./gradlew :app:assembleDebug
fi

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    exit 1
fi

# ---------------------------------------------------------------------------
# Step 2: Start the emulator container
# ---------------------------------------------------------------------------
echo ""
echo "[2/5] Starting emulator container..."
podman-compose -f docker-compose.test.yml up -d lava-emulator

# ---------------------------------------------------------------------------
# Step 3: Wait for emulator to be ready
# ---------------------------------------------------------------------------
echo ""
echo "[3/5] Waiting for emulator boot completion..."
BOOT_TIMEOUT=300
BOOT_START=$(date +%s)
while true; do
    # Check if the emulator container is still running
    if ! podman ps --filter name=lava-emulator --format '{{.Status}}' | grep -q "Up"; then
        echo "ERROR: Emulator container exited unexpectedly."
        podman logs lava-emulator | tail -30
        podman-compose -f docker-compose.test.yml down
        exit 1
    fi

    # Try to connect via adb and check boot completion
    if adb connect localhost:5555 >/dev/null 2>&1; then
        BOOT_COMPLETED=$(adb -s localhost:5555 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [ "$BOOT_COMPLETED" = "1" ]; then
            echo "Emulator ready."
            break
        fi
    fi

    NOW=$(date +%s)
    if [ $((NOW - BOOT_START)) -gt $BOOT_TIMEOUT ]; then
        echo "ERROR: Emulator boot timed out after ${BOOT_TIMEOUT}s."
        podman logs lava-emulator | tail -30
        podman-compose -f docker-compose.test.yml down
        exit 1
    fi
    echo "  Waiting for boot... ($((NOW - BOOT_START))s elapsed)"
    sleep 5
done

# ---------------------------------------------------------------------------
# Step 4: Install APK and run Challenge Tests
# ---------------------------------------------------------------------------
echo ""
echo "[4/5] Installing APK and running Challenge Tests..."

# Uninstall any previous version to avoid signature mismatch
adb -s localhost:5555 uninstall digital.vasic.lava.client.dev 2>/dev/null || true

# Install the debug APK
echo "Installing APK..."
adb -s localhost:5555 install -r "$APK_PATH"

# Grant permissions that the app needs for testing
adb -s localhost:5555 shell pm grant digital.vasic.lava.client.dev android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true

# Run the Challenge Tests
# Note: connectedAndroidTest runs all instrumentation tests in :app
# We also want to run provider-specific tests if they exist.
echo "Running connectedAndroidTest (Challenge Tests C1–C8 + provider e2e)..."
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=lava.app.challenges.ChallengeTestSuite

# ---------------------------------------------------------------------------
# Step 5: Collect results and cleanup
# ---------------------------------------------------------------------------
echo ""
echo "[5/5] Collecting test results..."

TEST_OUTPUT="app/build/outputs/androidTest-results/connected"
if [ -d "$TEST_OUTPUT" ]; then
    echo "Test results available at: $TEST_OUTPUT"
    ls -la "$TEST_OUTPUT"
else
    echo "No test output directory found at $TEST_OUTPUT"
fi

# Pull screenshots if any
SCREENSHOT_DIR="app/build/outputs/connected_android_test_additional_output"
if [ -d "$SCREENSHOT_DIR" ]; then
    echo "Screenshots available at: $SCREENSHOT_DIR"
fi

echo ""
echo "========================================"
echo "  Test run complete"
echo "  Stopping emulator container..."
echo "========================================"

podman-compose -f docker-compose.test.yml down

echo "Done."
