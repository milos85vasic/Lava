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
#       [--container-image REF]                            # containerized-runner image. Default:
#                                                            # ghcr.io/vasic-digital/lava-android-emulator:api{api}-x86_64
#                                                            # The {api} token is substituted per AVD
#                                                            # api level by the Containers runner
#                                                            # (LVA-014 fix #1); a REF without {api}
#                                                            # is used verbatim for every AVD.
#       [--container-runtime podman|docker]                # default: podman
#       [--extra-apk PATH]                                   # additional APK to install on each AVD
#                                                            # AFTER the client APK, before instrumentation
#                                                            # runs. Repeatable: pass --extra-apk more than
#                                                            # once to install several companion APKs. Forwarded
#                                                            # verbatim to the Containers CLI's own repeatable
#                                                            # --extra-apk flag (MatrixConfig.ExtraAPKPaths).
#                                                            # Use for Challenges needing a second app on the
#                                                            # SAME device (e.g. Challenge72's on-device api-app
#                                                            # companion for same-device mDNS discovery).
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
# LVA-014 (2026-07-26) durable device-gate fixes:
#   1. AVD-NAME RESOLUTION — the Containers runner resolves the AVD names
#      actually BAKED into each emulator image (`avdmanager list avd`
#      inside the image) instead of assuming the requested name exists.
#      The §6.AE.2 matrix names (CZ_API34_Phone, ...) are ADVISORY: on an
#      api-level match the image's baked AVD (e.g. "default") is booted;
#      an exact name match is used verbatim; no match fails FAST naming
#      the available baked AVDs. Root cause of the 2026-07-04 "boot
#      hang": the api34 image bakes exactly one AVD named "default", the
#      runner passed CZ_API34_Phone, the entrypoint exited in ~4s, and
#      WaitForBoot misreported it as a boot timeout.
#   2. CONTAINER LIVENESS — pkg/emulator Containerized.WaitForBoot now
#      checks the emulator container is still running on every poll
#      iteration and, on exit, fails immediately with the container's
#      captured logs (fetched before --rm reaps them).
#   3. IMAGE PREFLIGHT — before building/delegating, this script verifies
#      every image the matrix needs is present locally (instantiating
#      the {api} template per distinct api level) and pulls the missing
#      ones; a pull failure is an honest operator-facing error with the
#      local-build fallback command, never a silent skip.
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
declare -a EXTRA_APKS=()        # additional APKs installed on each AVD after the client APK
BOOT_TIMEOUT=""                 # forwarded to emulator-matrix --boot-timeout (default 5m).
                                # Raise on a loaded host where an ARM/HVF cold-boot legitimately
                                # exceeds 5m on contention (NOT a product defect). Empty = CLI default.
# §6.X containerized-runner image + runtime. The pinned Containers
# cmd/emulator-matrix CLI (>= 71d32562) REQUIRES --container-image when the
# resolved runner is containerized (Linux + /dev/kvm), and accepts
# --container-runtime (default podman). These are forwarded ONLY when the
# resolved runner is containerized; on a host-direct (macOS+HVF / Windows+WHPX)
# resolution the CLI ignores them.
#
# LVA-014 fix #1+#3 (2026-07-26): the default image reference carries the
# {api} TEMPLATE TOKEN, which the Containers runner substitutes with each
# AVD's api level at Boot time — one reference covers the whole multi-api
# §6.AE.2 matrix (api28 row → ...:api28-x86_64, api34 row → ...:api34-x86_64).
# An explicit --container-image without {api} is used verbatim for every AVD
# (the pre-LVA-014 behavior). The preflight below (fix #3) verifies every
# instantiated image exists locally and pulls the missing ones with an
# honest error on failure — no silent skip.
CONTAINER_IMAGE="ghcr.io/vasic-digital/lava-android-emulator:api{api}-x86_64"
CONTAINER_RUNTIME="podman"      # podman|docker

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
        --extra-apk)     EXTRA_APKS+=("$2"); shift 2 ;;
        --boot-timeout)  BOOT_TIMEOUT="$2"; shift 2 ;;
        --container-image)   CONTAINER_IMAGE="$2"; shift 2 ;;
        --container-runtime) CONTAINER_RUNTIME="$2"; shift 2 ;;
        --latest-api)    LATEST_API="$2"; shift 2 ;;
        --add-tv)        ADD_TV=1; shift ;;
        --add-foldable)  ADD_FOLDABLE=1; shift ;;
        --include-helixqa) INCLUDE_HELIXQA=1; shift ;;
        -h|--help)       sed -n '3,93p' "$0"; exit 0 ;;
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

# --- LVA-014 fix #3: emulator-image preflight (containerized runner only) ---
#
# Verify every emulator image the matrix needs is present LOCALLY, and
# pull the missing §6.AE.2 ones with clear progress. A pull failure is an
# honest operator-facing error (exit 1) — never a silent skip that later
# surfaces as a misleading per-AVD boot failure.
#
# Needed-image derivation:
#   - CONTAINER_IMAGE contains the {api} template token → instantiate it
#     once per DISTINCT api level in the AVD list (the default matrix
#     yields api28/api30/api34/api<latest>).
#   - CONTAINER_IMAGE has no {api} token → that single image is needed
#     verbatim (the pre-LVA-014 single-image behavior).
if [[ "$RESOLVED_RUNNER" == "containerized" ]]; then
    declare -a NEEDED_IMAGES=()
    if [[ "$CONTAINER_IMAGE" == *"{api}"* ]]; then
        declare -A SEEN_APIS=()
        IFS=',' read -ra AVD_ENTRIES <<< "$AVDS_JOINED"
        for entry in "${AVD_ENTRIES[@]}"; do
            api_level="$(echo "$entry" | cut -d: -f2 | tr -d '[:space:]')"
            if [[ -z "$api_level" || "$api_level" == "0" ]]; then
                echo "ERROR: AVD entry '$entry' has no api level but --container-image uses the {api} template" >&2
                exit 2
            fi
            if [[ -z "${SEEN_APIS[$api_level]:-}" ]]; then
                SEEN_APIS[$api_level]=1
                NEEDED_IMAGES+=("${CONTAINER_IMAGE//\{api\}/$api_level}")
            fi
        done
    else
        NEEDED_IMAGES+=("$CONTAINER_IMAGE")
    fi

    echo "==> LVA-014 image preflight: ${#NEEDED_IMAGES[@]} emulator image(s) required"
    PREFLIGHT_FAILED=0
    idx=0
    for img in "${NEEDED_IMAGES[@]}"; do
        idx=$((idx + 1))
        if "$CONTAINER_RUNTIME" image inspect "$img" >/dev/null 2>&1; then
            echo "    [$idx/${#NEEDED_IMAGES[@]}] present locally: $img"
            continue
        fi
        echo "    [$idx/${#NEEDED_IMAGES[@]}] MISSING locally: $img — pulling..."
        PULL_LOG="$(mktemp -t lava-image-pull-XXXXXX.log)"
        if "$CONTAINER_RUNTIME" pull "$img" 2>&1 | tee "$PULL_LOG"; then
            echo "    [$idx/${#NEEDED_IMAGES[@]}] pulled OK: $img"
            rm -f "$PULL_LOG"
        else
            # Honest failure (§6.J): name the image, surface the pull
            # error tail, and give the local-build fallback from
            # submodules/containers/pkg/emulator/Containerfile. NO silent
            # skip — a missing image the matrix needs is a gate blocker.
            api_hint="$(echo "$img" | sed -n 's/.*api\([0-9][0-9]*\).*/\1/p')"
            cat >&2 <<EOF

==> ERROR: required emulator image is unavailable: $img
    The image is not present locally and '$CONTAINER_RUNTIME pull' failed.
    Pull error (last lines):
$(tail -n 5 "$PULL_LOG" | sed 's/^/      /')
    Operator options:
      1. Build the image locally from submodules/containers/pkg/emulator/Containerfile:
           (cd submodules/containers && $CONTAINER_RUNTIME build \\
               --build-arg API_LEVEL=${api_hint:-NN} --build-arg ABI=x86_64 \\
               -f pkg/emulator/Containerfile -t $img .)
      2. Authenticate to the registry ('$CONTAINER_RUNTIME login ghcr.io') and re-run.
      3. Reduce the matrix with --avds to api levels whose images exist locally
         (development-iteration run, NOT a §6.AE.2-conformant gate matrix).
    This is an honest preflight failure (LVA-014 fix #3), not a silent skip.
EOF
            rm -f "$PULL_LOG"
            PREFLIGHT_FAILED=1
        fi
    done
    if [[ "$PREFLIGHT_FAILED" == "1" ]]; then
        exit 1
    fi
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

# --extra-apk is repeatable on the Containers CLI side (MatrixConfig.
# ExtraAPKPaths) — forward each operator-supplied path as its own
# --extra-apk occurrence, in the order given.
declare -a EXTRA_APK_ARGS=()
for extra_apk_path in "${EXTRA_APKS[@]:-}"; do
    if [[ -n "$extra_apk_path" ]]; then
        EXTRA_APK_ARGS+=(--extra-apk "$extra_apk_path")
    fi
done
if [[ "${#EXTRA_APK_ARGS[@]}" -gt 0 ]]; then
    echo "==> extra APKs to install alongside the client APK: ${EXTRA_APKS[*]}"
fi

# §6.X: the pinned cmd/emulator-matrix CLI (>= 71d32562) REQUIRES
# --container-image when the RESOLVED runner is containerized (Linux +
# /dev/kvm path). Forward it (plus --container-runtime) only in that case;
# on a host-direct resolution (macOS+HVF / Windows+WHPX) the CLI does not
# consult them. --image-manifest stays forwarded for backward compat
# (cache-routed system-image fetch on the containerized path).
declare -a CONTAINER_ARGS=()
if [[ "$RESOLVED_RUNNER" == "containerized" ]]; then
    CONTAINER_ARGS=(--container-image "$CONTAINER_IMAGE" --container-runtime "$CONTAINER_RUNTIME")
    echo "==> containerized runner: --container-image=$CONTAINER_IMAGE --container-runtime=$CONTAINER_RUNTIME"
fi

echo "==> Delegating to Containers/cmd/emulator-matrix --runner=auto (resolves to $RESOLVED_RUNNER on $PLATFORM)"
"$CONTAINERS_CLI" \
    --runner=auto \
    --apk "$APK" \
    --avds "$AVDS_JOINED" \
    --evidence-dir "$EVIDENCE_DIR" \
    --image-manifest tools/lava-containers/vm-images.json \
    "${CONTAINER_ARGS[@]}" \
    "${EXTRA_APK_ARGS[@]}" \
    ${BOOT_TIMEOUT:+--boot-timeout "$BOOT_TIMEOUT"} \
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
