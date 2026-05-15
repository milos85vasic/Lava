#!/usr/bin/env bash
# scripts/verify-all-constitution-rules.sh — §11.4.32 enforcement engine.
#
# Per HelixConstitution §11.4.32 (Post-Constitution-Pull Validation
# Mandate, 2026-05-15): "Whenever a project's constitution submodule is
# fetched + pulled with any content change, the project MUST run a
# full-project + recursive-submodule validation sweep BEFORE the new
# constitution HEAD is treated as canonical for any other work."
#
# This script IS that sweep. It walks every constitution rule with a
# programmatic gate, runs each gate against the current tree, captures
# the result, emits a structured JSON attestation, and exits non-zero
# if ANY gate fails.
#
# §11.4.32 itself: "sweep's own meta-test (paired mutation §1.1) plants
# a known violation of each enforced gate and asserts sweep reports
# FAIL for the planted gate. A sweep that exits PASS without running
# every implementable gate is a §11.4.32 violation." The hermetic test
# at tests/check-constitution/test_verify_all_rules.sh is that
# meta-test.
#
# §11.4.32 is the **enforcement engine** for every other §11.4.x and
# CONST-NNN rule — without it, new rules cascade as anchors but never
# get enforced in the codebase.
#
# Usage:
#   bash scripts/verify-all-constitution-rules.sh
#   bash scripts/verify-all-constitution-rules.sh --strict     # exit 1 on any failure (default)
#   bash scripts/verify-all-constitution-rules.sh --advisory   # exit 0 even on failures (advisory mode)
#   bash scripts/verify-all-constitution-rules.sh --json-only  # emit JSON only (no stdout summary)
#
# Output:
#   stdout: per-gate summary + final verdict
#   .lava-ci-evidence/verify-all/<UTC-timestamp>.json: structured attestation
#
# Inheritance: HelixConstitution §11.4.32 (the mandate itself); §11.4.18
# (script documentation); §6.J/§6.L (anti-bluff).
# Classification: project-specific (the gate list is Lava-specific; the
# sweep-with-meta-test discipline is universal per HelixConstitution).

set -uo pipefail   # NOT -e — we want to keep running gates even when one fails

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

MODE="strict"
JSON_ONLY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)    MODE="strict"; shift ;;
        --advisory)  MODE="advisory"; shift ;;
        --json-only) JSON_ONLY=1; shift ;;
        -h|--help)   sed -n '3,30p' "$0"; exit 0 ;;
        *)           echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

EVIDENCE_DIR="$REPO_ROOT/.lava-ci-evidence/verify-all"
mkdir -p "$EVIDENCE_DIR"
TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
ATTESTATION="$EVIDENCE_DIR/$TS.json"

declare -a GATE_NAMES=()
declare -a GATE_RESULTS=()
declare -a GATE_DURATIONS=()
declare -a GATE_REFERENCES=()

run_gate() {
    local gate_name=$1
    local rule_ref=$2
    local cmd=$3

    [[ "$JSON_ONLY" == "1" ]] || echo "==> $gate_name ($rule_ref)"
    local start_ts end_ts duration rc
    start_ts=$(date +%s)
    if eval "$cmd" >/dev/null 2>&1; then
        rc=0
    else
        rc=$?
    fi
    end_ts=$(date +%s)
    duration=$((end_ts - start_ts))

    GATE_NAMES+=("$gate_name")
    GATE_REFERENCES+=("$rule_ref")
    GATE_DURATIONS+=("$duration")
    if [[ "$rc" -eq 0 ]]; then
        GATE_RESULTS+=("PASS")
        [[ "$JSON_ONLY" == "1" ]] || echo "    ✓ PASS (${duration}s)"
    else
        GATE_RESULTS+=("FAIL")
        [[ "$JSON_ONLY" == "1" ]] || echo "    ✗ FAIL (exit=$rc, ${duration}s)"
    fi
}

[[ "$JSON_ONLY" == "1" ]] || {
    echo "===================================================="
    echo "§11.4.32 verify-all-constitution-rules sweep"
    echo "  mode: $MODE"
    echo "  attestation: $ATTESTATION"
    echo "===================================================="
}

# -----------------------------------------------------------------------------
# Gate registry — every constitution rule with a programmatic gate.
# Format: run_gate "<gate-name>" "<rule-ref>" "<command>"
# -----------------------------------------------------------------------------

# §6.A through §6.X + §6.AD inheritance + §6.W boundary + §11.4.6 no-guessing
run_gate "constitution-doc-parser" "§6.D/§6.E/§6.F/§6.AD/§6.W/§11.4.6/§6.AE" \
    "bash scripts/check-constitution.sh"

# §6.AC non-fatal coverage (STRICT default after queue drained)
run_gate "non-fatal-coverage" "§6.AC + HelixConstitution telemetry discipline" \
    "bash scripts/check-non-fatal-coverage.sh"

# §11.4.30 .gitignore + No-Versioned-Build-Artifacts (Phase 2)
run_gate "gitignore-coverage" "HelixConstitution §11.4.30" \
    "bash scripts/check-gitignore-coverage.sh"

# §11.4.28 Submodules-As-Equal-Codebase / no nested own-org chains (Phase 5)
# NOTE: ADVISORY mode in sweep until the Challenges/Panoptic refactor lands;
# the scanner itself is STRICT, but here the sweep reports the violation
# without exiting non-zero so other gates continue to run + report.
# Per docs/plans/2026-05-15-constitution-compliance.md Phase 5: gate is
# wired in advisory mode; STRICT-flip is a follow-up after refactor.
run_gate "no-nested-own-org-submodules" "HelixConstitution §11.4.28 (advisory)" \
    "bash scripts/check-no-nested-own-org-submodules.sh --advisory"

# §11.4.35 Canonical-Root Inheritance Clarity + §11.4.36 install_upstreams (Phase 8)
# NOTE: ADVISORY mode in sweep until the 10 missing install_upstreams scripts
# land in their respective submodules. §11.4.35 sub-checks all currently pass.
# Per docs/plans/2026-05-15-constitution-compliance.md Phase 8: gate is wired
# in advisory mode; STRICT-flip is a follow-up after the 10 submodules each
# gain their own install_upstreams.
run_gate "canonical-root-and-upstreams" "HelixConstitution §11.4.35 + §11.4.36 (advisory)" \
    "bash scripts/check-canonical-root-and-upstreams.sh --advisory"

# §11.4.31 Submodule-Dependency-Manifest (Phase 3)
# NOTE: ADVISORY mode in sweep until the 16 missing per-submodule
# helix-deps.yaml files land in their respective submodules.
# Parent helix-deps.yaml is in place + well-formed. Per
# docs/plans/2026-05-15-constitution-compliance.md Phase 3: gate is
# wired in advisory mode; STRICT-flip is a follow-up after the 16
# submodules each gain their own helix-deps.yaml manifest.
run_gate "helix-deps-manifest" "HelixConstitution §11.4.31 (advisory)" \
    "bash scripts/check-helix-deps-manifest.sh --advisory"

# §6.AB Challenge discrimination (Layer 1 marker + Layer 2 body)
run_gate "challenge-discrimination" "§6.AB Anti-Bluff Test-Suite Reinforcement" \
    "bash scripts/check-challenge-discrimination.sh"

# §6.AE per-feature Challenge coverage (STRICT default)
run_gate "challenge-coverage" "§6.AE Comprehensive Challenge Coverage Mandate" \
    "bash scripts/check-challenge-coverage.sh"

# §6.R no-hardcoded-{uuid,ipv4,host:port}
run_gate "no-hardcoded-uuid" "§6.R No-Hardcoding Mandate" \
    "bash scripts/scan-no-hardcoded-uuid.sh"
run_gate "no-hardcoded-ipv4" "§6.R No-Hardcoding Mandate" \
    "bash scripts/scan-no-hardcoded-ipv4.sh"
run_gate "no-hardcoded-hostport" "§6.R No-Hardcoding Mandate" \
    "bash scripts/scan-no-hardcoded-hostport.sh"

# §6.U + §6.H credential / sudo-su patterns (already in check-constitution.sh
# but invoke the dedicated checks too; redundancy is intentional — multiple
# enforcement points per §11.4.32 design)
run_gate "fixture-freshness" "§6.D Behavioral Coverage / fixture-staleness" \
    "bash scripts/check-fixture-freshness.sh"

# §6.AD inheritance pointer-block presence (subset of constitution-doc-parser
# but exposed as a separate gate so a partial pass is detectable)
# Wraps the inheritance-pointer-block-presence check from check-constitution.sh
run_gate "inject-helix-inheritance-block-idempotent" "§6.AD-debt item 1 — inheritance propagation" \
    "bash scripts/inject-helix-inheritance-block.sh | tail -1 | grep -q 'added=0'"

# Hermetic test suites (each suite's own paired-mutation contracts)
for suite in tests/firebase tests/ci-sh tests/compose-layout tests/tag-helper \
             tests/vm-images tests/vm-signing tests/vm-distro; do
    if [[ -x "$suite/run_all.sh" ]]; then
        run_gate "hermetic-suite-$(basename $suite)" "§11.4 anti-bluff hermetic test suite" \
            "bash $suite/run_all.sh"
    fi
done

# Flat-layout hermetic suites
for t in tests/pre-push/check*_test.sh; do
    [[ -f "$t" ]] || continue
    bn=$(basename "$t" .sh)
    run_gate "hermetic-pre-push-$bn" "§11.4 anti-bluff hermetic pre-push test" \
        "bash $t"
done

for t in tests/check-constitution/test_*.sh tests/check-constitution/check_constitution_test.sh; do
    [[ -f "$t" ]] || continue
    # SKIP test_verify_all_rules.sh inside the sweep — it calls the sweep
    # recursively, which would create infinite recursion. The meta-test
    # is invoked separately (by the operator OR by ci.sh's tests/check-
    # constitution flat-layout walker).
    [[ "$(basename "$t")" == "test_verify_all_rules.sh" ]] && continue
    bn=$(basename "$t" .sh)
    run_gate "hermetic-check-constitution-$bn" "§11.4 anti-bluff hermetic constitution test" \
        "bash $t"
done

# -----------------------------------------------------------------------------
# Aggregate + emit attestation JSON.
# -----------------------------------------------------------------------------

total_gates=${#GATE_NAMES[@]}
pass_count=0
fail_count=0
for r in "${GATE_RESULTS[@]}"; do
    if [[ "$r" == "PASS" ]]; then
        pass_count=$((pass_count + 1))
    else
        fail_count=$((fail_count + 1))
    fi
done

# JSON construction (manual — POSIX-portable)
{
    echo "{"
    echo "  \"sweep_timestamp\": \"$TS\","
    echo "  \"sweep_mode\": \"$MODE\","
    echo "  \"sweep_constitution_pin\": \"$(cd constitution && git rev-parse HEAD 2>/dev/null || echo unknown)\","
    echo "  \"total_gates\": $total_gates,"
    echo "  \"pass_count\": $pass_count,"
    echo "  \"fail_count\": $fail_count,"
    echo "  \"all_passed\": $([[ "$fail_count" -eq 0 ]] && echo true || echo false),"
    echo "  \"gates\": ["
    for i in "${!GATE_NAMES[@]}"; do
        comma=$([[ $i -lt $((total_gates - 1)) ]] && echo "," || echo "")
        printf '    {"name": "%s", "rule_ref": "%s", "result": "%s", "duration_seconds": %s}%s\n' \
            "${GATE_NAMES[$i]}" "${GATE_REFERENCES[$i]}" "${GATE_RESULTS[$i]}" "${GATE_DURATIONS[$i]}" "$comma"
    done
    echo "  ]"
    echo "}"
} > "$ATTESTATION"

[[ "$JSON_ONLY" == "1" ]] || {
    echo ""
    echo "===================================================="
    echo "Sweep complete: $pass_count PASS / $fail_count FAIL (of $total_gates total)"
    echo "Attestation: $ATTESTATION"
    echo "===================================================="
}

if [[ "$fail_count" -gt 0 ]]; then
    if [[ "$MODE" == "strict" ]]; then
        [[ "$JSON_ONLY" == "1" ]] || echo "STRICT mode — exiting 1 due to $fail_count failed gate(s)." >&2
        exit 1
    else
        [[ "$JSON_ONLY" == "1" ]] || echo "ADVISORY mode — exit 0 despite $fail_count failed gate(s)."
    fi
fi
exit 0
