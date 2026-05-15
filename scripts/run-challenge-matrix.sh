#!/usr/bin/env bash
# scripts/run-challenge-matrix.sh — §6.AE gate entry point.
#
# Per §6.AE.2 + §6.AE.3: gate-mode Challenge runs MUST execute on the
# §6.AE.2 minimum AVD matrix INSIDE containers managed by the
# Submodules/Containers/cmd/emulator-matrix CLI.
#
# This script is THIN GLUE — it pre-bakes the §6.AE.2 minimum matrix
# (API 28 / 30 / 34 / latest stable × phone + tablet) and forwards
# everything else to the Containers CLI per §6.X.
#
# Usage:
#
#   ./scripts/run-challenge-matrix.sh
#       [--test-class lava.app.challenges.ChallengeNN_Foo]   # default: ALL Challenges
#       [--evidence-dir .lava-ci-evidence/<tag>]             # default: dated dir
#       [--no-build]                                         # skip APK rebuild
#       [--latest-api 36]                                    # override "latest stable"
#       [--add-tv]                                           # add TV-class AVD when feature touches TvActivity
#       [--add-foldable]                                     # add foldable AVD
#
# Honest pre-flight: this script DETECTS the §6.X-debt darwin/arm64
# host gap (no /dev/kvm available to podman) and, when detected, REFUSES
# to claim it produced a §6.AE-conformant gate run. Instead it documents
# what was attempted and what the operator must do on a Linux x86_64
# gate-host to complete the matrix.
#
# Inheritance: HelixConstitution + Lava §6.AE + §6.X + §6.I.
# Classification: project-specific (Lava AVD list + APK paths; runtime
# delegation is universal per §6.X).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- defaults ---
TEST_CLASS=""                   # empty = run all Challenges
EVIDENCE_DIR=".lava-ci-evidence/$(date -u +%Y-%m-%dT%H-%M-%SZ)-challenge-matrix"
NO_BUILD=0
LATEST_API="36"                 # current "latest stable" as of 2026-05
ADD_TV=0
ADD_FOLDABLE=0

# §6.AE.2 minimum AVD matrix. Format: name:apiLevel:formFactor.
# This is the constitutional minimum for gate runs. Sub-minimums are
# permitted for development iteration; the gate row's `gating: true`
# flag is only set when the full minimum + every config dimension
# (theme/locale/density per §6.AE.2) is covered.
declare -a BASE_AVDS=(
    "CZ_API28_Phone:28:phone"
    "CZ_API30_Phone:30:phone"
    "CZ_API34_Phone:34:phone"
    "CZ_API34_Tablet:34:tablet"
)

# --- arg parse ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --test-class)    TEST_CLASS="$2"; shift 2 ;;
        --evidence-dir)  EVIDENCE_DIR="$2"; shift 2 ;;
        --no-build)      NO_BUILD=1; shift ;;
        --latest-api)    LATEST_API="$2"; shift 2 ;;
        --add-tv)        ADD_TV=1; shift ;;
        --add-foldable)  ADD_FOLDABLE=1; shift ;;
        -h|--help)       sed -n '3,33p' "$0"; exit 0 ;;
        *)               echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Add the latest-stable phone to BASE_AVDS (per §6.AE.2 mandatory minimum).
BASE_AVDS+=("CZ_API${LATEST_API}_Phone:${LATEST_API}:phone")

if [[ "$ADD_TV" == "1" ]]; then
    BASE_AVDS+=("CZ_API${LATEST_API}_TV:${LATEST_API}:tv")
fi
if [[ "$ADD_FOLDABLE" == "1" ]]; then
    BASE_AVDS+=("CZ_API${LATEST_API}_Foldable:${LATEST_API}:foldable")
fi

AVDS_JOINED=$(IFS=,; echo "${BASE_AVDS[*]}")

mkdir -p "$EVIDENCE_DIR"

echo "==> §6.AE Challenge matrix runner"
echo "    test class: ${TEST_CLASS:-<all under lava.app.challenges>}"
echo "    evidence dir: $EVIDENCE_DIR"
echo "    AVDs: $AVDS_JOINED"

# --- pre-flight: §6.X host gap detection ---
PLATFORM="$(uname -s)"
HOST_ARCH="$(uname -m)"
KVM_AVAILABLE=0
if [[ -e /dev/kvm ]]; then
    KVM_AVAILABLE=1
fi

cat > "$EVIDENCE_DIR/host-preflight.json" <<JSON
{
  "platform": "$PLATFORM",
  "host_arch": "$HOST_ARCH",
  "kvm_available_on_host": $KVM_AVAILABLE,
  "podman_machine_kvm_passthrough": "UNCONFIRMED — only verifiable inside the podman VM via 'podman machine ssh ls /dev/kvm'",
  "constitutional_status": "$(if [[ "$PLATFORM" == "Linux" && "$HOST_ARCH" == "x86_64" && "$KVM_AVAILABLE" == "1" ]]; then echo 'Gate-host eligible: §6.AE matrix CAN be executed'; else echo 'Gate-host INELIGIBLE: §6.AE.2/.5 BLOCKED — see §6.X-debt darwin-arm64-gap incident'; fi)"
}
JSON

if [[ "$PLATFORM" != "Linux" || "$HOST_ARCH" != "x86_64" || "$KVM_AVAILABLE" != "1" ]]; then
    cat <<EOF >&2
==> §6.AE.7 host-gap detected
    Platform: $PLATFORM / arch: $HOST_ARCH / KVM: $([[ "$KVM_AVAILABLE" == "1" ]] && echo "available" || echo "absent")

    This host CANNOT execute the §6.AE Challenge matrix because Android
    emulators inside containers require KVM (Linux x86_64) or HVF passthrough
    (macOS native, NOT exposed to podman containers).

    The standing §6.X-debt entry documents this gap:
      .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json

    What this script DID:
      - Validated arguments + matrix minimum (§6.AE.2 satisfied: API 28/30/34/$LATEST_API × phone+tablet)
      - Wrote $EVIDENCE_DIR/host-preflight.json with the host-gap classification
      - PROVABLY did NOT produce per-AVD attestation rows (no real run executed)

    What the operator MUST do on a Linux x86_64 + KVM gate-host:
      1. Provision the host with: podman + Android SDK + adb in PATH + a non-root user
      2. Clone Lava + run 'git submodule update --init --recursive'
      3. Run this script with the same arguments
      4. Inspect $EVIDENCE_DIR/real-device-verification.{md,json} for per-AVD rows

    This script EXITS 2 (gate-host ineligible) — NOT 0 (success) — because
    a §6.AE gate run requires the full matrix to actually run. Per §6.J/§6.L:
    no false-pass; honest unblock report.
EOF
    exit 2
fi

# --- on-gate-host: build (if not --no-build) + delegate to Containers CLI ---
if [[ "$NO_BUILD" == "0" ]]; then
    echo "==> Building debug APK"
    ./gradlew --no-daemon :app:assembleDebug
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at $APK after build" >&2
    exit 1
fi

CONTAINERS_CLI="Submodules/Containers/cmd/emulator-matrix/emulator-matrix"
if [[ ! -x "$CONTAINERS_CLI" ]]; then
    echo "==> Building Containers cmd/emulator-matrix"
    (cd Submodules/Containers && go build -o cmd/emulator-matrix/emulator-matrix ./cmd/emulator-matrix/)
fi

# Per §6.AE.3: gate runs MUST use --runner=containerized
declare -a TEST_CLASS_ARGS=()
if [[ -n "$TEST_CLASS" ]]; then
    TEST_CLASS_ARGS=(--test-class "$TEST_CLASS")
fi

echo "==> Delegating to Containers/cmd/emulator-matrix --runner=containerized"
"$CONTAINERS_CLI" \
    --runner=containerized \
    --apk "$APK" \
    --avds "$AVDS_JOINED" \
    --evidence-dir "$EVIDENCE_DIR" \
    --image-manifest tools/lava-containers/vm-images.json \
    --cold-boot \
    "${TEST_CLASS_ARGS[@]}"
RC=$?

echo ""
echo "==> §6.AE matrix run complete (exit=$RC)"
echo "    Evidence: $EVIDENCE_DIR/real-device-verification.{md,json}"
exit "$RC"
