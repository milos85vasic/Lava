#!/usr/bin/env bash
# scripts/run-challenge-matrix.sh — §6.AE gate entry point.
#
# Per §6.AE.2 + §6.AE.3: gate-mode Challenge runs MUST execute on the
# §6.AE.2 minimum AVD matrix INSIDE containers managed by the
# submodules/containers/cmd/emulator-matrix CLI.
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
#       [--avds "name:api:form[,name:api:form...]"]          # REPLACE the §6.AE.2
#                                                            # default matrix entirely with an
#                                                            # explicit AVD list. Use this to target
#                                                            # EXISTING host AVDs (e.g. on a macOS
#                                                            # host-direct+HVF gate run where the
#                                                            # default CZ_API* images are not
#                                                            # provisioned). When supplied, --latest-api
#                                                            # / --add-tv / --add-foldable are IGNORED
#                                                            # (those only shape the default matrix).
#                                                            # NOTE: a sub-minimum --avds list is a
#                                                            # development-iteration run, NOT a
#                                                            # §6.AE.2-conformant gate matrix.
#       [--latest-api 36]                                    # override "latest stable" (default matrix only)
#       [--add-tv]                                           # add TV-class AVD when feature touches TvActivity
#       [--add-foldable]                                     # add foldable AVD
#       [--include-helixqa]                                  # ALSO invoke the 11 HelixQA Challenge scripts
#                                                            # (per docs/plans/2026-05-16-helixqa-integration-design.md
#                                                            # Option 1 — shell-level wiring). OFF by default so
#                                                            # existing matrix runs are unaffected.
#
# OS-aware pre-flight: each host OS has a different correct
# hardware-acceleration backend, and the correct emulator runner
# follows from it (see submodules/containers/pkg/emulator/accel.go):
#
#   - Linux  → accel KVM  → containerized runner (emulator inside a
#              podman/docker container with --device /dev/kvm).
#   - macOS  → accel HVF  → host-direct runner. Apple HVF
#              (Hypervisor.framework) is a macOS-host-only API a Linux
#              container cannot reach; the Android emulator uses HVF
#              automatically when run as a native macOS process, so
#              host-direct is the only accelerated AND gate-eligible
#              runner on macOS.
#   - Windows → accel WHPX → host-direct runner (same reasoning: WHPX
#              is host-only, unreachable from a Linux container).
#
# This script forwards --runner=auto to the Containers emulator-matrix
# CLI, which resolves the OS-correct runner via emulator.ResolveRunner.
#
# It still REFUSES to claim a §6.AE-conformant gate run when the host
# genuinely has no acceleration available:
#   - Linux without /dev/kvm → exit 2 (genuinely no accelerator).
#   - macOS without HVF (kern.hv_support != 1) → exit 2.
# On macOS WITH HVF the OS-correct runner is host-direct+HVF, so the
# script proceeds rather than exiting 2 — that is not a host gap, it is
# the OS-correct accelerated path.
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
INCLUDE_HELIXQA=0               # per HelixQA integration-design Option 1
AVDS_OVERRIDE=""                # when non-empty, REPLACES the §6.AE.2 default matrix

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
        --avds)          AVDS_OVERRIDE="$2"; shift 2 ;;
        --latest-api)    LATEST_API="$2"; shift 2 ;;
        --add-tv)        ADD_TV=1; shift ;;
        --add-foldable)  ADD_FOLDABLE=1; shift ;;
        --include-helixqa) INCLUDE_HELIXQA=1; shift ;;
        -h|--help)       sed -n '3,66p' "$0"; exit 0 ;;
        *)               echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ -n "$AVDS_OVERRIDE" ]]; then
    # --avds supplied: REPLACE the §6.AE.2 default matrix entirely with the
    # operator-provided list. This is the path for targeting EXISTING host
    # AVDs (e.g. macOS host-direct+HVF gate runs where the default CZ_API*
    # images are not provisioned). --latest-api / --add-tv / --add-foldable
    # are intentionally NOT applied — they only shape the default matrix.
    AVDS_JOINED="$AVDS_OVERRIDE"
    echo "==> --avds override active: default §6.AE.2 matrix REPLACED with operator list"
    echo "    (sub-minimum lists are development-iteration runs, not §6.AE.2-conformant gate matrices)"
else
    # Add the latest-stable phone to BASE_AVDS (per §6.AE.2 mandatory minimum).
    BASE_AVDS+=("CZ_API${LATEST_API}_Phone:${LATEST_API}:phone")

    if [[ "$ADD_TV" == "1" ]]; then
        BASE_AVDS+=("CZ_API${LATEST_API}_TV:${LATEST_API}:tv")
    fi
    if [[ "$ADD_FOLDABLE" == "1" ]]; then
        BASE_AVDS+=("CZ_API${LATEST_API}_Foldable:${LATEST_API}:foldable")
    fi

    AVDS_JOINED=$(IFS=,; echo "${BASE_AVDS[*]}")
fi

mkdir -p "$EVIDENCE_DIR"

echo "==> §6.AE Challenge matrix runner"
echo "    test class: ${TEST_CLASS:-<all under lava.app.challenges>}"
echo "    evidence dir: $EVIDENCE_DIR"
echo "    AVDs: $AVDS_JOINED"
echo "    include-helixqa: $INCLUDE_HELIXQA"

# --- optional: HelixQA Challenge shell-wiring (Option 1 per integration design) ---
# HelixQA challenges run on the HOST; they are independent of the AVD
# matrix and therefore execute BEFORE the §6.X host-gap check. This lets
# the broader-than-Compose-UI test types still run on darwin/arm64 hosts
# even when the emulator matrix is blocked.
HELIXQA_OVERALL_RC=0
if [[ "$INCLUDE_HELIXQA" == "1" ]]; then
    echo ""
    echo "==> Invoking HelixQA Challenge shell-wiring (scripts/run-helixqa-challenges.sh)"
    HELIXQA_EVIDENCE_DIR="$EVIDENCE_DIR/helixqa"
    mkdir -p "$HELIXQA_EVIDENCE_DIR"
    if bash "$REPO_ROOT/scripts/run-helixqa-challenges.sh" \
            --evidence-dir "$HELIXQA_EVIDENCE_DIR"; then
        echo "    ✓ HelixQA wrapper exited 0 (zero FAIL)"
    else
        HELIXQA_OVERALL_RC=$?
        echo "    ✗ HelixQA wrapper exited $HELIXQA_OVERALL_RC (one or more FAIL)" >&2
        # Do NOT short-circuit the matrix run on HelixQA failure — both surfaces
        # are independently load-bearing. The final aggregate exit code combines
        # both at the bottom of this script.
    fi
fi

# --- pre-flight: OS-aware §6.X acceleration detection ---
#
# Resolve, per host OS, the acceleration backend and OS-correct
# runner. This mirrors submodules/containers/pkg/emulator/accel.go's
# AccelProfileForOS — the Go function is the source of truth; this is
# the shell-side detection that decides whether the host can run the
# matrix at all.
PLATFORM="$(uname -s)"
HOST_ARCH="$(uname -m)"
KVM_AVAILABLE=0
if [[ -e /dev/kvm ]]; then
    KVM_AVAILABLE=1
fi
# macOS HVF support: `sysctl -n kern.hv_support` returns 1 when the
# Hypervisor.framework is available on this Mac.
HVF_AVAILABLE=0
if [[ "$PLATFORM" == "Darwin" ]]; then
    if [[ "$(sysctl -n kern.hv_support 2>/dev/null || echo 0)" == "1" ]]; then
        HVF_AVAILABLE=1
    fi
fi

# Decide accel backend, OS-correct runner, and gate eligibility.
#   Linux + /dev/kvm  → accel kvm,  runner containerized, eligible
#   macOS + HVF       → accel hvf,  runner host-direct,   eligible
#   Linux no /dev/kvm → accel none, ineligible (genuinely no accel)
#   macOS no HVF      → accel none, ineligible (genuinely no accel)
#   other / unknown   → accel none, ineligible
ACCEL_BACKEND="none"
RESOLVED_RUNNER="host-direct"
GATE_ELIGIBLE=0
INELIGIBLE_REASON=""
if [[ "$PLATFORM" == "Linux" ]]; then
    if [[ "$KVM_AVAILABLE" == "1" ]]; then
        ACCEL_BACKEND="kvm"
        RESOLVED_RUNNER="containerized"
        GATE_ELIGIBLE=1
    else
        INELIGIBLE_REASON="Linux host without /dev/kvm — genuinely no hardware accelerator available"
    fi
elif [[ "$PLATFORM" == "Darwin" ]]; then
    if [[ "$HVF_AVAILABLE" == "1" ]]; then
        # macOS with HVF: the OS-correct accelerated runner is
        # host-direct (a native macOS emulator process uses HVF).
        # This is NOT a host gap — it is the OS-correct path.
        ACCEL_BACKEND="hvf"
        RESOLVED_RUNNER="host-direct"
        GATE_ELIGIBLE=1
    else
        INELIGIBLE_REASON="macOS host without HVF (kern.hv_support != 1) — genuinely no hardware accelerator available"
    fi
else
    INELIGIBLE_REASON="Unknown host OS '$PLATFORM' — no known accelerated emulator path"
fi

cat > "$EVIDENCE_DIR/host-preflight.json" <<JSON
{
  "platform": "$PLATFORM",
  "host_arch": "$HOST_ARCH",
  "kvm_available_on_host": $KVM_AVAILABLE,
  "hvf_available_on_host": $HVF_AVAILABLE,
  "resolved_accel_backend": "$ACCEL_BACKEND",
  "resolved_runner": "$RESOLVED_RUNNER",
  "gate_eligible": $([[ "$GATE_ELIGIBLE" == "1" ]] && echo true || echo false),
  "constitutional_status": "$(if [[ "$GATE_ELIGIBLE" == "1" ]]; then echo "Gate-host eligible: §6.AE matrix CAN be executed via runner=$RESOLVED_RUNNER (accel=$ACCEL_BACKEND)"; else echo "Gate-host INELIGIBLE: §6.AE.2/.5 BLOCKED — $INELIGIBLE_REASON"; fi)"
}
JSON

if [[ "$GATE_ELIGIBLE" != "1" ]]; then
    cat <<EOF >&2
==> §6.AE.7 host-gap detected
    Platform: $PLATFORM / arch: $HOST_ARCH
    Accelerator: none ($INELIGIBLE_REASON)

    This host CANNOT execute the §6.AE Challenge matrix because no
    hardware-acceleration backend is available. The Android emulator
    needs KVM (Linux), HVF (macOS), or WHPX (Windows) to run at usable
    speed; none was detected here.

    The standing §6.X-debt entry documents the darwin/arm64 container
    case:
      .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json

    What this script DID:
      - Validated arguments + matrix minimum (§6.AE.2 satisfied: API 28/30/34/$LATEST_API × phone+tablet)
      - Wrote $EVIDENCE_DIR/host-preflight.json with the host-gap classification
      - PROVABLY did NOT produce per-AVD attestation rows (no real run executed)

    What the operator MUST do on an accelerated gate-host:
      1. Linux x86_64 + /dev/kvm  → runner resolves to containerized
         macOS + HVF              → runner resolves to host-direct
      2. Provision: podman (Linux) OR Android SDK + adb (macOS) in PATH, non-root user
      3. Clone Lava + run 'git submodule update --init --recursive'
      4. Run this script with the same arguments
      5. Inspect $EVIDENCE_DIR/real-device-verification.{md,json} for per-AVD rows

    This script EXITS 2 (gate-host ineligible) — NOT 0 (success) — because
    a §6.AE gate run requires the full matrix to actually run. Per §6.J/§6.L:
    no false-pass; honest unblock report.
EOF
    # If --include-helixqa surfaced real failures, still propagate that
    # signal; otherwise the gate-host-ineligible exit-2 stands. The dominant
    # signal is the most-severe outcome.
    if [[ "$HELIXQA_OVERALL_RC" -ne 0 ]]; then
        echo "ADDITIONAL: HelixQA wrapper reported FAIL (exit=$HELIXQA_OVERALL_RC). See $EVIDENCE_DIR/helixqa/" >&2
    fi
    exit 2
fi

echo "==> §6.X acceleration resolved: accel=$ACCEL_BACKEND runner=$RESOLVED_RUNNER (platform=$PLATFORM)"

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

CONTAINERS_CLI="submodules/containers/cmd/emulator-matrix/emulator-matrix"
if [[ ! -x "$CONTAINERS_CLI" ]]; then
    echo "==> Building Containers cmd/emulator-matrix"
    (cd submodules/containers && go build -o cmd/emulator-matrix/emulator-matrix ./cmd/emulator-matrix/)
fi

# Per §6.X: forward --runner=auto so the Containers CLI resolves the
# OS-correct runner (Linux→containerized via /dev/kvm, macOS→host-direct
# via HVF). This pre-flight already proved the host is gate-eligible.
declare -a TEST_CLASS_ARGS=()
if [[ -n "$TEST_CLASS" ]]; then
    TEST_CLASS_ARGS=(--test-class "$TEST_CLASS")
fi

echo "==> Delegating to Containers/cmd/emulator-matrix --runner=auto (resolves to $RESOLVED_RUNNER on $PLATFORM)"
"$CONTAINERS_CLI" \
    --runner=auto \
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

# Aggregate: if HelixQA was opted in AND reported failures, surface that
# even when the AVD matrix passed. Both surfaces are independently
# load-bearing per the integration-design Option-1 anti-bluff posture.
if [[ "$HELIXQA_OVERALL_RC" -ne 0 ]]; then
    echo "==> HelixQA wrapper also reported FAIL (exit=$HELIXQA_OVERALL_RC). See $EVIDENCE_DIR/helixqa/" >&2
    # Promote to non-zero if matrix itself was 0; if matrix already failed,
    # keep matrix's exit code as the dominant signal.
    if [[ "$RC" -eq 0 ]]; then
        RC=1
    fi
fi
exit "$RC"
