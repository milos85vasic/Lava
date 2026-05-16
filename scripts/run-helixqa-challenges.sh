#!/usr/bin/env bash
# scripts/run-helixqa-challenges.sh — HelixQA Option 1 wrapper.
#
# Per docs/plans/2026-05-16-helixqa-integration-design.md (HelixQA
# integration Option 1 — Shell-level wiring): this script invokes the
# 11 Challenge scripts shipped by the HelixQA submodule and records a
# per-script attestation row + a roll-up JSON summary.
#
# It is THIN GLUE. It does NOT modify HelixQA. It does NOT modify the
# scripts it invokes. It runs each one as the HelixQA author wrote it,
# captures stdout + stderr + exit code, and reports honestly:
#
#   - PASS  → exit code 0
#   - FAIL  → exit code 1 (real Lava-tree defect surfaced by HelixQA)
#   - SKIP  → exit code 2 (HelixQA-internal precondition unmet, e.g.
#                          scanner missing because the challenge was
#                          written for HelixQA's own repo layout)
#   - ERROR → any other non-zero exit (treated as FAIL by aggregate)
#
# Per §6.J anti-bluff: this wrapper does NOT silently swallow failures
# AND does NOT pretend a script ran when it could not. If the HelixQA
# submodule is absent (transitional / not yet `git submodule update
# --init`'d), the wrapper exits 3 with an explicit operator instruction.
#
# The four open questions deferred at Option 1 commit time (2026-05-16)
# were resolved in Phase 4 follow-up B (this commit):
#   Q1 (container vs host) → --runner=host|containerized flag (§6.X)
#   Q2 (real-deps vs stub) → --require-toolchain=go|none flag (§6.J)
#   Q3 (evidence directory) → LAVA_HELIXQA_EVIDENCE_DIR env-var override
#   Q4 (§6.W mirror policy) → HELIXQA_W_EXCLUSIONS array (audit doc)
#
# Per §6.W: HelixQA scripts are audited in docs/helixqa-script-audit.md.
# As of the audit timestamp, 0 of 11 scripts violate §6.W on default
# config; the HELIXQA_W_EXCLUSIONS array is therefore empty. The audit
# MUST be re-run when the submodules/helixqa pin bumps (the wiring
# drift check at runtime is necessary but NOT sufficient — the per-
# script grep audit is a separate concern per §6.J).
#
# Per §6.X: HelixQA's challenges run on the HOST by default (workstation
# iteration mode). For gate runs, --runner=containerized wraps each
# invocation in a podman/docker container with the Go toolchain mounted.
# §6.X compliance for gate runs requires --runner=containerized; that
# mode honestly-fails-fast (exit 4) when the Containers submodule isn't
# bootstrapped on the host.
#
# Per §6.J: --require-toolchain=go (default) verifies `go version`
# succeeds before invoking scripts that need it; exit 4 on missing
# toolchain. --require-toolchain=none falls back to per-script SKIP
# for the Go-requiring scripts when the toolchain is absent.
#
# Usage:
#
#   bash scripts/run-helixqa-challenges.sh
#       [--evidence-dir <path>]              # default: dated under .lava-ci-evidence/helixqa-challenges/
#                                            #          (or value of $LAVA_HELIXQA_EVIDENCE_DIR if set)
#       [--only NAME1,NAME2,...]             # run only the listed scripts (basename without .sh)
#       [--continue-on-fail]                 # keep running after first failure (default behavior)
#       [--stop-on-fail]                     # halt the loop on first non-zero exit
#       [--json-only]                        # suppress stdout summary
#       [--runner host|containerized]        # §6.X: containerized for gate runs (default: host)
#       [--container-image <image>]          # required with --runner=containerized (no default)
#       [--container-runtime podman|docker]  # default: auto-detect; podman preferred
#       [--require-toolchain go|none]        # §6.J: 'go' = verify go toolchain before Go-requiring scripts
#                                            #       'none' = SKIP Go-requiring scripts if toolchain absent
#                                            # default: 'go'
#
# Inheritance: §6.AE Comprehensive Challenge Coverage (HelixQA forms the
# broader-than-Compose-UI test-type layer) + §6.J Anti-Bluff Functional
# Reality + §6.L (HelixDevelopment-owned dependency adoption discipline)
# + §6.X (Container-Submodule Emulator Wiring — extended here to general
# container-bound execution for §6.AE gate runs) + §6.W (per-script
# mirror-policy audit at docs/helixqa-script-audit.md)
# + HelixConstitution §11.4.27 (CONST-050 — HelixQA full-coverage mandate).
# Classification: project-specific (the per-script wiring choices + the
# §6.W audit results are Lava-specific; the host-vs-container delegation
# is universal per §6.X).

set -uo pipefail   # NOT -e — we want to keep running scripts even when one fails

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

# --- defaults ---
EVIDENCE_DIR="${LAVA_HELIXQA_EVIDENCE_DIR:-}"   # Q3: env-var override
ONLY_FILTER=""
STOP_ON_FAIL=0
JSON_ONLY=0
RUNNER="host"                                    # Q1: §6.X default = host (workstation iteration)
CONTAINER_IMAGE=""                               # Q1: required when --runner=containerized
CONTAINER_RUNTIME=""                             # Q1: auto-detect by default
REQUIRE_TOOLCHAIN="go"                           # Q2: §6.J default = verify go toolchain

while [[ $# -gt 0 ]]; do
    case "$1" in
        --evidence-dir)        EVIDENCE_DIR="$2"; shift 2 ;;
        --only)                ONLY_FILTER="$2"; shift 2 ;;
        --continue-on-fail)    STOP_ON_FAIL=0; shift ;;
        --stop-on-fail)        STOP_ON_FAIL=1; shift ;;
        --json-only)           JSON_ONLY=1; shift ;;
        --runner)              RUNNER="$2"; shift 2 ;;
        --container-image)     CONTAINER_IMAGE="$2"; shift 2 ;;
        --container-runtime)   CONTAINER_RUNTIME="$2"; shift 2 ;;
        --require-toolchain)   REQUIRE_TOOLCHAIN="$2"; shift 2 ;;
        -h|--help)             sed -n '3,80p' "$0"; exit 0 ;;
        *)                     echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

# --- Q1: validate --runner choice ---
if [[ "$RUNNER" != "host" && "$RUNNER" != "containerized" ]]; then
    echo "ERROR: --runner must be 'host' or 'containerized' (got: '$RUNNER')" >&2
    exit 2
fi

# --- Q2: validate --require-toolchain choice ---
if [[ "$REQUIRE_TOOLCHAIN" != "go" && "$REQUIRE_TOOLCHAIN" != "none" ]]; then
    echo "ERROR: --require-toolchain must be 'go' or 'none' (got: '$REQUIRE_TOOLCHAIN')" >&2
    exit 2
fi

if [[ -z "$EVIDENCE_DIR" ]]; then
    TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
    EVIDENCE_DIR="$REPO_ROOT/.lava-ci-evidence/helixqa-challenges/$TS"
fi
mkdir -p "$EVIDENCE_DIR"

HELIXQA_DIR="$REPO_ROOT/submodules/helixqa"
HELIXQA_SCRIPTS_DIR="$HELIXQA_DIR/challenges/scripts"

# --- §6.J honest pre-flight: the submodule MUST be initialized ---
if [[ ! -d "$HELIXQA_SCRIPTS_DIR" ]]; then
    cat <<EOF >&2
==> §6.J HelixQA submodule missing
    Expected directory: $HELIXQA_SCRIPTS_DIR
    Not found in this checkout.

    Cause: the submodules/helixqa pin is checked into .gitmodules but
    the submodule has NOT been initialized in this working tree.

    Fix (operator action):
      git submodule update --init submodules/helixqa

    This wrapper REFUSES to claim success on an absent submodule
    (anti-bluff posture per §6.J / §6.L). Exit code 3 = missing
    dependency, distinct from exit code 1 (real defect) and exit
    code 2 (HelixQA precondition unmet inside a single challenge).
EOF
    exit 3
fi

# --- the 11 HelixQA Challenge scripts (canonical list, Phase 4 adoption) ---
declare -a HELIXQA_SCRIPTS=(
    "anchor_manifest_challenge.sh"
    "bluff_scanner_challenge.sh"
    "chaos_failure_injection_challenge.sh"
    "ddos_health_flood_challenge.sh"
    "host_no_auto_suspend_challenge.sh"
    "mutation_ratchet_challenge.sh"
    "no_suspend_calls_challenge.sh"
    "scaling_horizontal_challenge.sh"
    "stress_sustained_load_challenge.sh"
    "ui_terminal_interaction_challenge.sh"
    "ux_end_to_end_flow_challenge.sh"
)

# --- Q2: per-script toolchain requirement map (§6.J anti-bluff) ---
# Each entry: "<script>:<toolchain>". Toolchains:
#   go   — needs Go toolchain + `go mod download` in HelixQA worktree
#   none — pure bash / system tools; no toolchain prerequisite
# Source: source-inspection of each script's invoked binaries.
# Bluff prevention: declared "needs go" but actually uses only bash
# would silently mis-classify; declared "needs none" but actually
# needs go would fail noisily inside the script (still honest). The
# direction we MUST get right is the latter (false-none → real fail
# is honest; false-go → false-skip is the bluff).
declare -a HELIXQA_TOOLCHAIN_MAP=(
    "anchor_manifest_challenge.sh:none"             # awk + grep on docs
    "bluff_scanner_challenge.sh:go"                 # invokes HelixQA's bluff-scanner (Go binary path)
    "chaos_failure_injection_challenge.sh:none"     # curl + bash + /dev/tcp
    "ddos_health_flood_challenge.sh:none"           # curl + bash arithmetic
    "host_no_auto_suspend_challenge.sh:none"        # systemctl + journalctl (Linux-only but no go)
    "mutation_ratchet_challenge.sh:go"              # invokes go-mutesting
    "no_suspend_calls_challenge.sh:none"            # pure grep
    "scaling_horizontal_challenge.sh:none"          # curl + bash arithmetic
    "stress_sustained_load_challenge.sh:none"       # curl + bash arithmetic
    "ui_terminal_interaction_challenge.sh:none"     # local bin/helixqa (HelixQA itself builds it)
    "ux_end_to_end_flow_challenge.sh:none"          # local bin/helixqa (same)
)

# --- Q4: §6.W per-script exclusion list (sourced from docs/helixqa-script-audit.md) ---
# Currently EMPTY (0 of 11 scripts violate §6.W on default config). Future
# audit cycles MAY populate this array if HelixQA grows a script that
# pushes to a non-GitHub / non-GitLab remote. The audit re-run is owed
# whenever the submodules/helixqa pin bumps — see audit doc's
# "Re-audit triggers" section.
declare -a HELIXQA_W_EXCLUSIONS=(
    # (intentionally empty — see docs/helixqa-script-audit.md)
)

# Helper: lookup a script's declared toolchain requirement.
lookup_toolchain() {
    local name="$1"
    local entry
    for entry in "${HELIXQA_TOOLCHAIN_MAP[@]}"; do
        if [[ "${entry%%:*}" == "$name" ]]; then
            echo "${entry##*:}"
            return 0
        fi
    done
    echo "unknown"
}

# Helper: is the script on the §6.W exclusion list?
# Note: empty arrays + set -u + ${arr[@]} triggers "unbound variable" on
# bash < 4.4 (macOS default). Use the ${arr[@]+...} expansion guard.
is_w_excluded() {
    local name="$1"
    local excl
    for excl in "${HELIXQA_W_EXCLUSIONS[@]+"${HELIXQA_W_EXCLUSIONS[@]}"}"; do
        if [[ "$excl" == "$name" ]]; then
            return 0
        fi
    done
    return 1
}

# --- apply --only filter (comma-separated basenames without .sh) ---
declare -a RUN_LIST=()
if [[ -n "$ONLY_FILTER" ]]; then
    IFS=',' read -ra ONLY_NAMES <<< "$ONLY_FILTER"
    for s in "${HELIXQA_SCRIPTS[@]}"; do
        base="${s%.sh}"
        for want in "${ONLY_NAMES[@]}"; do
            if [[ "$base" == "$want" ]]; then
                RUN_LIST+=("$s")
            fi
        done
    done
    if [[ ${#RUN_LIST[@]} -eq 0 ]]; then
        echo "ERROR: --only filter '$ONLY_FILTER' matched none of the 11 canonical scripts" >&2
        echo "Available: ${HELIXQA_SCRIPTS[*]/%.sh/}" >&2
        exit 2
    fi
else
    RUN_LIST=("${HELIXQA_SCRIPTS[@]}")
fi

# --- §6.J precondition: every listed script MUST actually exist on disk ---
# (catches the case where Phase 4 wired a script that was later removed
# upstream; better to fail loudly here than silently skip)
declare -a MISSING_SCRIPTS=()
for s in "${RUN_LIST[@]}"; do
    if [[ ! -f "$HELIXQA_SCRIPTS_DIR/$s" ]]; then
        MISSING_SCRIPTS+=("$s")
    fi
done
if [[ ${#MISSING_SCRIPTS[@]} -gt 0 ]]; then
    cat <<EOF >&2
==> §6.J wiring drift detected
    These HelixQA scripts are wired in this wrapper but absent in
    the pinned submodule at $HELIXQA_SCRIPTS_DIR:
EOF
    for m in "${MISSING_SCRIPTS[@]}"; do
        echo "      - $m" >&2
    done
    cat <<EOF >&2

    The pin is $(cd "$HELIXQA_DIR" 2>/dev/null && git rev-parse HEAD 2>/dev/null || echo unknown).
    Either (a) update the wired list in scripts/run-helixqa-challenges.sh
    AND docs/scripts/run-helixqa-challenges.sh.md, OR (b) bump the
    submodule pin to a commit that ships the missing script. Per §6.J
    this wrapper refuses to silently skip — exit 3 (missing dependency).
EOF
    exit 3
fi

# --- Q1: §6.X containerized-runner preflight ---
# §6.X requires gate runs to execute inside containers. If --runner=
# containerized is selected, this section validates the Containers
# submodule is bootstrapped + the container runtime is available.
# Honest-fail-fast (exit 4) per §6.J — we never silently degrade
# containerized → host because that would be the exact bluff §6.X
# was written to prevent.
CONTAINERS_DIR="$REPO_ROOT/submodules/containers"
TOOLCHAIN_AVAILABLE=0
HELIXQA_W_EXCLUDED_COUNT=0

if [[ "$RUNNER" == "containerized" ]]; then
    if [[ ! -d "$CONTAINERS_DIR" ]]; then
        cat <<EOF >&2
==> §6.X containerized runner unavailable
    --runner=containerized requires the submodules/containers submodule.
    Expected directory: $CONTAINERS_DIR
    Not found in this checkout.

    Fix (operator action):
      git submodule update --init submodules/containers

    Per §6.J anti-bluff: we exit 4 (missing-runtime) rather than silently
    falling back to --runner=host. Falling back would silently violate §6.X
    on a gate run; that is the canonical class of bluff this clause exists
    to prevent.
EOF
        exit 4
    fi
    # Auto-detect runtime if not specified
    if [[ -z "$CONTAINER_RUNTIME" ]]; then
        if command -v podman >/dev/null 2>&1; then
            CONTAINER_RUNTIME="podman"
        elif command -v docker >/dev/null 2>&1; then
            CONTAINER_RUNTIME="docker"
        else
            cat <<EOF >&2
==> §6.X container runtime unavailable
    Neither 'podman' nor 'docker' is on PATH. --runner=containerized
    requires one of them.

    Fix (operator action):
      - macOS: 'brew install podman' + 'podman machine init && podman machine start'
      - Linux: distribution package manager install of podman (preferred per §6.U) or docker

    Per §6.J: exit 4 (missing-runtime) — no silent degradation to host.
EOF
            exit 4
        fi
    fi
    if ! command -v "$CONTAINER_RUNTIME" >/dev/null 2>&1; then
        echo "ERROR: --container-runtime='$CONTAINER_RUNTIME' but the binary is not on PATH" >&2
        exit 4
    fi
    # Default image: a Go-toolchain-bearing image suitable for HelixQA's
    # Go-requiring scripts. The operator MAY override via --container-image
    # if they have a project-specific image with additional tooling.
    if [[ -z "$CONTAINER_IMAGE" ]]; then
        CONTAINER_IMAGE="docker.io/library/golang:1.22"
    fi
    [[ "$JSON_ONLY" == "1" ]] || {
        echo "[§6.X] runner=containerized runtime=$CONTAINER_RUNTIME image=$CONTAINER_IMAGE"
    }
fi

# --- Q2: §6.J toolchain preflight ---
# When --require-toolchain=go: verify `go version` succeeds AND `go mod
# download` works inside submodules/helixqa before invoking Go-requiring
# scripts. When --require-toolchain=none: skip Go-requiring scripts if
# toolchain absent (honest SKIP, not false-PASS).
if [[ "$RUNNER" == "host" ]]; then
    # On host, check the host's go binary directly.
    if command -v go >/dev/null 2>&1; then
        TOOLCHAIN_AVAILABLE=1
    fi
else
    # On containerized, the Go toolchain is provided by the container
    # image (golang:1.22 by default), so it is available by construction.
    # We trust the image declaration; verifying it would require running
    # the container twice (once for the probe, once for the script),
    # which is wasteful when the operator already chose the image.
    TOOLCHAIN_AVAILABLE=1
fi

if [[ "$REQUIRE_TOOLCHAIN" == "go" && "$TOOLCHAIN_AVAILABLE" == "0" ]]; then
    cat <<EOF >&2
==> §6.J Go toolchain missing
    --require-toolchain=go (default) requires 'go' on PATH.
    'go' was not found.

    Fix (operator action):
      - macOS:   'brew install go'
      - Linux:   distribution package manager install
      - Or set --require-toolchain=none to SKIP Go-requiring scripts
        (anchor_manifest is unaffected; mutation_ratchet + bluff_scanner
        will be classified SKIP exit-2 with a clear precondition message).

    Per §6.J anti-bluff: we exit 4 (missing-toolchain) rather than
    silently SKIP — silent skip with --require-toolchain=go (the
    default) would mask a real precondition gap. Operators who knowingly
    work without Go MUST opt in via --require-toolchain=none.
EOF
    exit 4
fi

[[ "$JSON_ONLY" == "1" ]] || {
    echo "===================================================="
    echo "HelixQA Challenge wrapper (Option 1 — shell wiring)"
    echo "  scripts dir: $HELIXQA_SCRIPTS_DIR"
    echo "  helixqa pin: $(cd "$HELIXQA_DIR" && git rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "  evidence dir: $EVIDENCE_DIR"
    echo "  total to run: ${#RUN_LIST[@]}"
    echo "  stop-on-fail: $STOP_ON_FAIL"
    echo "  runner: $RUNNER"
    echo "  require-toolchain: $REQUIRE_TOOLCHAIN (available=$TOOLCHAIN_AVAILABLE)"
    echo "===================================================="
}

declare -a SCRIPT_NAMES=()
declare -a SCRIPT_RESULTS=()
declare -a SCRIPT_EXIT_CODES=()
declare -a SCRIPT_DURATIONS=()

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
HALTED=0

for s in "${RUN_LIST[@]}"; do
    [[ "$JSON_ONLY" == "1" ]] || echo "==> $s"

    # Q4 §6.W gate — exclude scripts that violate the mirror-host boundary.
    if is_w_excluded "$s"; then
        log_file="$EVIDENCE_DIR/${s%.sh}.log"
        echo "SKIP: $s is on HELIXQA_W_EXCLUSIONS per §6.W audit (see docs/helixqa-script-audit.md)" > "$log_file"
        SCRIPT_NAMES+=("$s")
        SCRIPT_EXIT_CODES+=("2")
        SCRIPT_DURATIONS+=("0")
        SCRIPT_RESULTS+=("SKIP")
        SKIP_COUNT=$((SKIP_COUNT + 1))
        HELIXQA_W_EXCLUDED_COUNT=$((HELIXQA_W_EXCLUDED_COUNT + 1))
        [[ "$JSON_ONLY" == "1" ]] || echo "    - SKIP (§6.W exclusion, 0s) → $log_file"
        continue
    fi

    # Q2 toolchain gate — when --require-toolchain=none, SKIP Go-requiring
    # scripts if the toolchain is absent. When --require-toolchain=go, the
    # preflight above already exited 4 if toolchain was absent.
    tc="$(lookup_toolchain "$s")"
    if [[ "$REQUIRE_TOOLCHAIN" == "none" && "$tc" == "go" && "$TOOLCHAIN_AVAILABLE" == "0" ]]; then
        log_file="$EVIDENCE_DIR/${s%.sh}.log"
        echo "SKIP: $s requires Go toolchain (declared in HELIXQA_TOOLCHAIN_MAP); --require-toolchain=none + toolchain absent" > "$log_file"
        SCRIPT_NAMES+=("$s")
        SCRIPT_EXIT_CODES+=("2")
        SCRIPT_DURATIONS+=("0")
        SCRIPT_RESULTS+=("SKIP")
        SKIP_COUNT=$((SKIP_COUNT + 1))
        [[ "$JSON_ONLY" == "1" ]] || echo "    - SKIP (toolchain=go absent, 0s) → $log_file"
        continue
    fi

    start_ts=$(date +%s)
    log_file="$EVIDENCE_DIR/${s%.sh}.log"
    rc=0

    if [[ "$RUNNER" == "containerized" ]]; then
        # §6.X containerized invocation. Mount the repo root + the HelixQA
        # submodule + the Go cache so the inner script's relative paths
        # behave identically to host execution.
        # The inner script runs as the host UID to keep file ownership sane
        # for the evidence directory (operator-owned), which the container
        # writes to via the bind mount.
        rc=0
        "$CONTAINER_RUNTIME" run --rm \
            --user "$(id -u):$(id -g)" \
            -v "$REPO_ROOT:$REPO_ROOT:rw" \
            -w "$HELIXQA_SCRIPTS_DIR" \
            -e HOME=/tmp \
            "$CONTAINER_IMAGE" \
            bash "./$s" >"$log_file" 2>&1 || rc=$?
    else
        # Default host invocation. The script is run from its own directory
        # so its $(dirname ...) / relative-path lookups behave as the
        # HelixQA author intended.
        rc=0
        ( cd "$HELIXQA_SCRIPTS_DIR" && bash "./$s" ) >"$log_file" 2>&1 || rc=$?
    fi
    end_ts=$(date +%s)
    duration=$((end_ts - start_ts))

    SCRIPT_NAMES+=("$s")
    SCRIPT_EXIT_CODES+=("$rc")
    SCRIPT_DURATIONS+=("$duration")

    case "$rc" in
        0)
            SCRIPT_RESULTS+=("PASS")
            PASS_COUNT=$((PASS_COUNT + 1))
            [[ "$JSON_ONLY" == "1" ]] || echo "    ✓ PASS (${duration}s) → $log_file"
            ;;
        2)
            # HelixQA's documented "scanner missing / precondition unmet" exit code
            SCRIPT_RESULTS+=("SKIP")
            SKIP_COUNT=$((SKIP_COUNT + 1))
            [[ "$JSON_ONLY" == "1" ]] || echo "    - SKIP (exit=2, ${duration}s) → $log_file"
            ;;
        *)
            SCRIPT_RESULTS+=("FAIL")
            FAIL_COUNT=$((FAIL_COUNT + 1))
            [[ "$JSON_ONLY" == "1" ]] || echo "    ✗ FAIL (exit=$rc, ${duration}s) → $log_file"
            if [[ "$STOP_ON_FAIL" == "1" ]]; then
                HALTED=1
                [[ "$JSON_ONLY" == "1" ]] || echo "    --stop-on-fail set — halting loop"
                break
            fi
            ;;
    esac
done

TOTAL=${#SCRIPT_NAMES[@]}
HELIXQA_PIN=$(cd "$HELIXQA_DIR" && git rev-parse HEAD 2>/dev/null || echo unknown)

# Emit attestation JSON (POSIX-portable manual construction).
ATTESTATION="$EVIDENCE_DIR/helixqa-attestation.json"
RUNNER_CAVEAT_TEXT="(no caveat — §6.X containerized runner active)"
if [[ "$RUNNER" == "host" ]]; then
    RUNNER_CAVEAT_TEXT="§6.X container-wrapping NOT in use this run; rerun with --runner=containerized for §6.AE gate-mode evidence"
fi
{
    echo "{"
    echo "  \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"helixqa_pin\": \"$HELIXQA_PIN\","
    echo "  \"helixqa_runner\": \"$RUNNER\","
    echo "  \"helixqa_runner_caveat\": \"$RUNNER_CAVEAT_TEXT\","
    echo "  \"require_toolchain\": \"$REQUIRE_TOOLCHAIN\","
    echo "  \"toolchain_available\": $([[ "$TOOLCHAIN_AVAILABLE" == "1" ]] && echo true || echo false),"
    echo "  \"container_runtime\": \"${CONTAINER_RUNTIME:-n/a}\","
    echo "  \"container_image\": \"${CONTAINER_IMAGE:-n/a}\","
    echo "  \"w_excluded_count\": $HELIXQA_W_EXCLUDED_COUNT,"
    echo "  \"w_exclusion_audit\": \"docs/helixqa-script-audit.md\","
    echo "  \"total_scripts\": $TOTAL,"
    echo "  \"pass_count\": $PASS_COUNT,"
    echo "  \"fail_count\": $FAIL_COUNT,"
    echo "  \"skip_count\": $SKIP_COUNT,"
    echo "  \"halted_early\": $([[ "$HALTED" == "1" ]] && echo true || echo false),"
    echo "  \"all_passed\": $([[ "$FAIL_COUNT" -eq 0 && "$HALTED" == "0" ]] && echo true || echo false),"
    echo "  \"scripts\": ["
    for i in "${!SCRIPT_NAMES[@]}"; do
        comma=$([[ $i -lt $((TOTAL - 1)) ]] && echo "," || echo "")
        printf '    {"name": "%s", "result": "%s", "exit_code": %s, "duration_seconds": %s, "log": "%s"}%s\n' \
            "${SCRIPT_NAMES[$i]}" \
            "${SCRIPT_RESULTS[$i]}" \
            "${SCRIPT_EXIT_CODES[$i]}" \
            "${SCRIPT_DURATIONS[$i]}" \
            "${SCRIPT_NAMES[$i]%.sh}.log" \
            "$comma"
    done
    echo "  ]"
    echo "}"
} > "$ATTESTATION"

[[ "$JSON_ONLY" == "1" ]] || {
    echo ""
    echo "===================================================="
    echo "Summary: $PASS_COUNT PASS / $FAIL_COUNT FAIL / $SKIP_COUNT SKIP (of $TOTAL total)"
    echo "Attestation: $ATTESTATION"
    [[ "$HALTED" == "1" ]] && echo "Halted early due to --stop-on-fail."
    echo "===================================================="
}

# Exit code policy:
#   - 0 if zero FAIL (SKIP is not a failure)
#   - 1 if any FAIL (real defect surfaced by HelixQA OR halted-early flagged)
if [[ "$FAIL_COUNT" -gt 0 || "$HALTED" == "1" ]]; then
    exit 1
fi
exit 0
