#!/usr/bin/env bash
# scripts/run-release-canary-containerized.sh — §6.Z release-build cold-start
# canary via the Containers submodule's containerized (podman/docker) runner.
#
# Sibling to scripts/run-release-canary.sh (LVA-077), which uses a Genymotion
# VM as its §6.AH-authorized non-host-direct surface. This script exists
# because Genymotion is not installed/available on every gate host (this one
# included — `gmtool` is absent), so a host without Genymotion had NO way to
# verify a release-signed APK at all: instrumented Challenge Tests cannot run
# against it (AGP does not generate a matching androidTest variant, and
# forcing isDebuggable=true to work around that DISABLES R8 entirely,
# confirmed live: "WARNING: BuildType 'release' is both debuggable and has
# 'isMinifyEnabled' set to true. All code optimizations and obfuscation are
# disabled for debuggable builds." — proving nothing about the real artifact).
#
# This delegates to submodules/containers/cmd/emulator-canary, which installs
# the REAL (non-debuggable, R8-minified) release APK on a containerized KVM
# emulator, launches its main activity via `adb shell am start`, and watches
# logcat for a bounded window for FATAL / AndroidRuntime crash signatures —
# added specifically to close this gap (see the Containers submodule commit
# that added CanaryConfig.Emu + RunADBCommand + emulator-canary --runner).
#
# Usage:
#   ./scripts/run-release-canary-containerized.sh --apk <path> --package <pkg> [options]
#
#   Required:
#     --apk PATH               Host path to the release-signed APK
#     --package PKG            Android package name (e.g. digital.vasic.lava.client)
#
#   Optional:
#     --activity SPEC          Launch activity (default: .MainActivity)
#     --avd SPEC                AVD spec Name[:APILevel[:FormFactor]] (default: CZ_API34_Phone:34:phone)
#     --evidence-dir DIR        Where to write the canary attestation (default: dated dir under .lava-ci-evidence/)
#     --container-image REF     Default: ghcr.io/vasic-digital/lava-android-emulator:api{api}-x86_64
#     --container-runtime RT    podman|docker (default: podman)
#     --no-build                Skip building the emulator-canary binary (reuse existing)
#
# Exit codes (propagated from emulator-canary):
#   0 — activity resumed AND no FATAL detected (canary PASS)
#   1 — activity did not resume OR FATAL detected (canary FAIL)
#   2 — configuration error
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

APK=""
PACKAGE=""
ACTIVITY=".MainActivity"
AVD="CZ_API34_Phone:34:phone"
EVIDENCE_DIR=""
CONTAINER_IMAGE="ghcr.io/vasic-digital/lava-android-emulator:api{api}-x86_64"
CONTAINER_RUNTIME="podman"
NO_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --activity) ACTIVITY="$2"; shift 2 ;;
        --avd) AVD="$2"; shift 2 ;;
        --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
        --container-image) CONTAINER_IMAGE="$2"; shift 2 ;;
        --container-runtime) CONTAINER_RUNTIME="$2"; shift 2 ;;
        --no-build) NO_BUILD=1; shift ;;
        *) echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$APK" || -z "$PACKAGE" ]]; then
    echo "ERROR: --apk and --package are required" >&2
    exit 2
fi
if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at $APK" >&2
    exit 2
fi
if [[ -z "$EVIDENCE_DIR" ]]; then
    EVIDENCE_DIR=".lava-ci-evidence/release-canary-containerized-$(basename "$APK" .apk)-$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "$EVIDENCE_DIR"

CLI="submodules/containers/cmd/emulator-canary/emulator-canary"
if [[ "$NO_BUILD" == "0" || ! -x "$CLI" ]]; then
    echo "==> Building Containers cmd/emulator-canary"
    (cd submodules/containers && GOMAXPROCS=2 go build -o cmd/emulator-canary/emulator-canary ./cmd/emulator-canary/)
fi

echo "==> Running containerized release canary: apk=$APK package=$PACKAGE activity=$ACTIVITY avd=$AVD"
set +e
"$CLI" \
    --runner=auto \
    --apk "$APK" \
    --package "$PACKAGE" \
    --activity "$ACTIVITY" \
    --avd "$AVD" \
    --evidence-dir "$EVIDENCE_DIR" \
    --container-image "$CONTAINER_IMAGE" \
    --container-runtime "$CONTAINER_RUNTIME" \
    --cold-boot \
    --json
RC=$?
set -e

echo ""
echo "==> Release canary complete (exit=$RC). Evidence: $EVIDENCE_DIR/canary-attestation.json"
exit $RC
