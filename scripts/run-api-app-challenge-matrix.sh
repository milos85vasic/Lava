#!/usr/bin/env bash
# scripts/run-api-app-challenge-matrix.sh — §6.AE / §6.X / §6.AG gate entry
# point for the :api-app module's Compose UI Challenge tests
# (Challenge01..Challenge04 under lava.api.app.challenges).
#
# This is the :api-app sibling of scripts/run-challenge-matrix.sh. Like that
# script it is THIN GLUE: it delegates the emulator boot + lifecycle to the
# Containers submodule's cmd/emulator-matrix CLI (per §6.X / §6.AG — the device
# MUST come from a Containers-orchestrated cold-booted emulator AVD, NEVER a
# live/physical ADB device). On darwin/arm64 --runner=auto resolves to
# host-direct+HVF (the §6.X-resolved macOS gate runner; a Linux container cannot
# reach HVF/, so host-direct+HVF IS the macOS gate path — still
# Containers-orchestrated, still an emulator).
#
# Usage:
#   ./scripts/run-api-app-challenge-matrix.sh
#       [--test-class lava.api.app.challenges.ChallengeNN_Foo]  # default: all C01-04
#       [--avds "name:api:form[,...]"]   # default: Pixel_8:35:phone (provisioned AVD)
#       [--evidence-dir <dir>]           # default: dated dir under phase-e-api-app
#       [--no-build]                     # skip the :api-app APK rebuild
#
# Constitutional note (§6.AG / §6.X): the emulator boot is Containers-driven.
# The Containers cmd/emulator-matrix CLI now exposes a generic --gradle-module
# flag (landed in Containers commit 9a61a153; bare module name, default "app";
# the runner targets :<module>:connectedDebugAndroidTest at BOTH RunInstrumentation
# call sites — host-direct android.go + container containerized.go). This script
# forwards --gradle-module "${GRADLE_MODULE#:}" so the :api-app instrumentation
# tests actually run against the :api-app module instead of a 0-test false-green
# against :app (§6.Z/§6.J).
#
# Classification: project-specific (Lava :api-app module + APK paths; the
# emulator-orchestration delegation is universal per §6.X).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- defaults ---
TEST_CLASS=""          # empty = all Challenge classes under lava.api.app.challenges
GRADLE_MODULE=":api-app"
AVDS_OVERRIDE="Pixel_8:35:phone"   # the provisioned macOS-host AVD (§6.AG --avds path)
EVIDENCE_DIR=".lava-ci-evidence/phase-e-api-app/$(date -u +%Y-%m-%dT%H-%M-%SZ)-gate"
NO_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --test-class)    TEST_CLASS="$2"; shift 2 ;;
        --gradle-module) GRADLE_MODULE="$2"; shift 2 ;;
        --avds)          AVDS_OVERRIDE="$2"; shift 2 ;;
        --evidence-dir)  EVIDENCE_DIR="$2"; shift 2 ;;
        --no-build)      NO_BUILD=1; shift ;;
        -h|--help)       sed -n '3,40p' "$0"; exit 0 ;;
        *)               echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Default: run all four api-app Challenge classes. The Containers CLI takes a
# single --test-class; a comma-separated list is accepted by gradle's
# `class=` runner arg, so we enumerate the four classes explicitly.
if [[ -z "$TEST_CLASS" ]]; then
    TEST_CLASS="lava.api.app.challenges.Challenge01ApiAppColdStartTest,lava.api.app.challenges.Challenge02ApiAppBootAndServeTest,lava.api.app.challenges.Challenge03StopRestartTest,lava.api.app.challenges.Challenge04NotificationActionsTest"
fi

mkdir -p "$EVIDENCE_DIR"

echo "==> §6.AE/§6.X/§6.AG :api-app Challenge matrix runner"
echo "    gradle module: $GRADLE_MODULE"
echo "    test class(es): $TEST_CLASS"
echo "    AVDs: $AVDS_OVERRIDE"
echo "    evidence dir: $EVIDENCE_DIR"

# --- build the :api-app APKs (debug + androidTest) unless --no-build ---
if [[ "$NO_BUILD" == "0" ]]; then
    echo "==> Building $GRADLE_MODULE debug + androidTest APKs (--max-workers=2, §6.T.2)"
    GOMAXPROCS=2 nice -n 19 ./gradlew --no-daemon --max-workers=2 \
        "${GRADLE_MODULE}:assembleDebug" "${GRADLE_MODULE}:assembleDebugAndroidTest"
fi

# api-app debug APK path (the artifact the emulator-matrix CLI installs).
APK="api-app/build/outputs/apk/debug/api-app-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at $APK after build" >&2
    exit 1
fi

# --- §6.X acceleration pre-flight (mirror run-challenge-matrix.sh) ---
PLATFORM="$(uname -s)"; HOST_ARCH="$(uname -m)"
KVM_AVAILABLE=0; [[ -e /dev/kvm ]] && KVM_AVAILABLE=1
HVF_AVAILABLE=0
if [[ "$PLATFORM" == "Darwin" ]]; then
    [[ "$(sysctl -n kern.hv_support 2>/dev/null || echo 0)" == "1" ]] && HVF_AVAILABLE=1
fi
ACCEL_BACKEND="none"; GATE_ELIGIBLE=0; INELIGIBLE_REASON=""
if [[ "$PLATFORM" == "Linux" && "$KVM_AVAILABLE" == "1" ]]; then
    ACCEL_BACKEND="kvm"; GATE_ELIGIBLE=1
elif [[ "$PLATFORM" == "Darwin" && "$HVF_AVAILABLE" == "1" ]]; then
    ACCEL_BACKEND="hvf"; GATE_ELIGIBLE=1
else
    INELIGIBLE_REASON="no hardware accelerator (need /dev/kvm on Linux or HVF on macOS)"
fi
cat > "$EVIDENCE_DIR/host-preflight.json" <<JSON
{
  "platform": "$PLATFORM",
  "host_arch": "$HOST_ARCH",
  "kvm_available_on_host": $KVM_AVAILABLE,
  "hvf_available_on_host": $HVF_AVAILABLE,
  "resolved_accel_backend": "$ACCEL_BACKEND",
  "gate_eligible": $([[ "$GATE_ELIGIBLE" == "1" ]] && echo true || echo false)
}
JSON
if [[ "$GATE_ELIGIBLE" != "1" ]]; then
    echo "==> §6.AE.7 host-gap: $INELIGIBLE_REASON — gate-host ineligible, EXIT 2" >&2
    exit 2
fi
echo "==> §6.X acceleration resolved: accel=$ACCEL_BACKEND (platform=$PLATFORM)"

# --- build the Containers emulator-matrix CLI (the §6.X/§6.AG orchestrator) ---
CONTAINERS_CLI="submodules/containers/cmd/emulator-matrix/emulator-matrix"
if [[ ! -x "$CONTAINERS_CLI" ]]; then
    echo "==> Building Containers cmd/emulator-matrix"
    (cd submodules/containers && GOMAXPROCS=2 nice -n 19 \
        go build -o cmd/emulator-matrix/emulator-matrix ./cmd/emulator-matrix/)
fi

# --- delegate the emulator boot + instrumentation to the Containers CLI ---
# Per §6.X/§6.AG: --runner=auto resolves to host-direct+HVF on macOS (the
# Containers-orchestrated macOS gate runner). The CLI boots the AVD, installs
# $APK, runs the instrumentation against the --gradle-module module, and tears
# down.
echo "==> Delegating to Containers/cmd/emulator-matrix --runner=auto (module=$GRADLE_MODULE)"
"$CONTAINERS_CLI" \
    --gradle-module "${GRADLE_MODULE#:}" \
    --runner=auto \
    --apk "$APK" \
    --avds "$AVDS_OVERRIDE" \
    --test-class "$TEST_CLASS" \
    --evidence-dir "$EVIDENCE_DIR" \
    --image-manifest tools/lava-containers/vm-images.json \
    --cold-boot \
    --concurrent=1
RC=$?

echo ""
echo "==> :api-app matrix run complete (exit=$RC)"
echo "    Evidence: $EVIDENCE_DIR/real-device-verification.{md,json}"
exit "$RC"
