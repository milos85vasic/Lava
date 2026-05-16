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
# Per §6.X: HelixQA's challenges currently run on the HOST. Future
# cycles SHOULD wrap them inside the Containers submodule's runtime
# (open question #1 in the integration-design doc). This wrapper
# documents that gap in the attestation under `helixqa_runner: host`.
#
# Usage:
#
#   bash scripts/run-helixqa-challenges.sh
#       [--evidence-dir <path>]    # default: dated under .lava-ci-evidence/helixqa-challenges/
#       [--only NAME1,NAME2,...]   # run only the listed scripts (basename without .sh)
#       [--continue-on-fail]       # keep running after first failure (default behavior)
#       [--stop-on-fail]           # halt the loop on first non-zero exit
#       [--json-only]              # suppress stdout summary
#
# Inheritance: §6.AE Comprehensive Challenge Coverage (HelixQA forms the
# broader-than-Compose-UI test-type layer) + §6.J Anti-Bluff Functional
# Reality + §6.L (HelixDevelopment-owned dependency adoption discipline)
# + HelixConstitution §11.4.27 (CONST-050 — HelixQA full-coverage mandate).
# Classification: project-specific (the per-script wiring choices are
# Lava-specific; the host-vs-container delegation is universal per §6.X).

set -uo pipefail   # NOT -e — we want to keep running scripts even when one fails

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

# --- defaults ---
EVIDENCE_DIR=""
ONLY_FILTER=""
STOP_ON_FAIL=0
JSON_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --evidence-dir)        EVIDENCE_DIR="$2"; shift 2 ;;
        --only)                ONLY_FILTER="$2"; shift 2 ;;
        --continue-on-fail)    STOP_ON_FAIL=0; shift ;;
        --stop-on-fail)        STOP_ON_FAIL=1; shift ;;
        --json-only)           JSON_ONLY=1; shift ;;
        -h|--help)             sed -n '3,45p' "$0"; exit 0 ;;
        *)                     echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$EVIDENCE_DIR" ]]; then
    TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
    EVIDENCE_DIR="$REPO_ROOT/.lava-ci-evidence/helixqa-challenges/$TS"
fi
mkdir -p "$EVIDENCE_DIR"

HELIXQA_DIR="$REPO_ROOT/Submodules/HelixQA"
HELIXQA_SCRIPTS_DIR="$HELIXQA_DIR/challenges/scripts"

# --- §6.J honest pre-flight: the submodule MUST be initialized ---
if [[ ! -d "$HELIXQA_SCRIPTS_DIR" ]]; then
    cat <<EOF >&2
==> §6.J HelixQA submodule missing
    Expected directory: $HELIXQA_SCRIPTS_DIR
    Not found in this checkout.

    Cause: the Submodules/HelixQA pin is checked into .gitmodules but
    the submodule has NOT been initialized in this working tree.

    Fix (operator action):
      git submodule update --init Submodules/HelixQA

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

[[ "$JSON_ONLY" == "1" ]] || {
    echo "===================================================="
    echo "HelixQA Challenge wrapper (Option 1 — shell wiring)"
    echo "  scripts dir: $HELIXQA_SCRIPTS_DIR"
    echo "  helixqa pin: $(cd "$HELIXQA_DIR" && git rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "  evidence dir: $EVIDENCE_DIR"
    echo "  total to run: ${#RUN_LIST[@]}"
    echo "  stop-on-fail: $STOP_ON_FAIL"
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
    start_ts=$(date +%s)
    log_file="$EVIDENCE_DIR/${s%.sh}.log"
    # Invoke the HelixQA script unchanged. Capture stdout + stderr.
    # The script is run from its own directory so its $(dirname ...) /
    # relative-path lookups behave as the HelixQA author intended.
    rc=0
    ( cd "$HELIXQA_SCRIPTS_DIR" && bash "./$s" ) >"$log_file" 2>&1 || rc=$?
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
{
    echo "{"
    echo "  \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"helixqa_pin\": \"$HELIXQA_PIN\","
    echo "  \"helixqa_runner\": \"host\","
    echo "  \"helixqa_runner_caveat\": \"§6.X container-wrapping owed in future cycle per integration-design Open Q#1\","
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
