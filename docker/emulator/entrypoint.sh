#!/bin/bash
set -euo pipefail

# =============================================================================
# Lava Emulator Container Entrypoint
# =============================================================================
# Starts the Android emulator and keeps it running until signalled to stop.
# Expects /dev/kvm from the host and ANDROID_SDK_ROOT pointing to a valid SDK.
# =============================================================================

AVD_NAME="${AVD_NAME:-Pixel_9a}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"

# Verify KVM access
if [ ! -e /dev/kvm ]; then
    echo "ERROR: /dev/kvm not available in container."
    echo "Start the container with --privileged and -v /dev/kvm:/dev/kvm"
    exit 1
fi

if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "ERROR: /dev/kvm exists but is not accessible. Check permissions."
    exit 1
fi

echo "KVM access verified."

# Verify SDK exists
if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    echo "ERROR: ANDROID_SDK_ROOT ($ANDROID_SDK_ROOT) does not exist."
    echo "Mount the host Android SDK into the container."
    exit 1
fi

# Export paths
export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Verify emulator binary exists
if [ ! -f "$ANDROID_SDK_ROOT/emulator/emulator" ]; then
    echo "ERROR: emulator binary not found at $ANDROID_SDK_ROOT/emulator/emulator"
    exit 1
fi

# Verify AVD exists
if ! "$ANDROID_SDK_ROOT/emulator/emulator" -list-avds | grep -qx "$AVD_NAME"; then
    echo "ERROR: AVD '$AVD_NAME' not found."
    echo "Available AVDs:"
    "$ANDROID_SDK_ROOT/emulator/emulator" -list-avds
    exit 1
fi

echo "Starting emulator: $AVD_NAME"
echo "Android SDK: $ANDROID_SDK_ROOT"

# Start emulator in background with no-window for headless CI
# -no-snapshot: do not load/save snapshot (faster boot, reproducible)
# -gpu swiftshader_indirect: software rendering, works in container
# -no-audio: no audio device needed
# -no-boot-anim: skip boot animation for faster startup
"$ANDROID_SDK_ROOT/emulator/emulator" \
    -avd "$AVD_NAME" \
    -no-window \
    -no-snapshot \
    -gpu swiftshader_indirect \
    -no-audio \
    -no-boot-anim \
    -port 5554 \
    &

EMULATOR_PID=$!
echo "Emulator PID: $EMULATOR_PID"

# Wait for adb daemon to be ready
sleep 2
adb start-server || true

# Wait for device to be available
adb wait-for-device

# Wait for boot completion
echo "Waiting for boot completion..."
BOOT_TIMEOUT=300
BOOT_START=$(date +%s)
while true; do
    BOOT_COMPLETED=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "$BOOT_COMPLETED" = "1" ]; then
        echo "Boot completed."
        break
    fi

    # Check if emulator process is still alive
    if ! kill -0 $EMULATOR_PID 2>/dev/null; then
        echo "ERROR: Emulator process exited unexpectedly."
        wait $EMULATOR_PID || true
        exit 1
    fi

    NOW=$(date +%s)
    if [ $((NOW - BOOT_START)) -gt $BOOT_TIMEOUT ]; then
        echo "ERROR: Boot timed out after ${BOOT_TIMEOUT}s."
        kill $EMULATOR_PID || true
        exit 1
    fi

    sleep 5
done

# Print device info
adb shell getprop ro.product.model || true
adb shell getprop ro.build.version.release || true
adb shell getprop ro.build.version.sdk || true

echo "Emulator is ready. Keeping container alive..."

# Keep container running until signalled
wait $EMULATOR_PID
