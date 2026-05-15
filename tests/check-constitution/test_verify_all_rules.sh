#!/usr/bin/env bash
# Tests for scripts/verify-all-constitution-rules.sh — the §11.4.32
# enforcement-engine sweep. Per §11.4.32 itself: "sweep's own meta-test
# (paired mutation §1.1) plants a known violation of each enforced gate
# and asserts sweep reports FAIL for the planted gate."
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWEEP="$REPO_ROOT/scripts/verify-all-constitution-rules.sh"

# Test 1: clean tree → sweep reports all PASS + exits 0
test_clean_tree_passes() {
    cd "$REPO_ROOT"
    bash "$SWEEP" --json-only > /dev/null 2>&1
    local rc=$?
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_clean_tree_passes: expected exit 0 on clean tree, got $rc"
        exit 1
    fi
    local latest
    latest=$(ls -t "$REPO_ROOT"/.lava-ci-evidence/verify-all/*.json 2>/dev/null | head -1)
    if [[ -z "$latest" ]] || ! grep -q '"all_passed": true' "$latest"; then
        echo "FAIL test_clean_tree_passes: expected all_passed=true in latest attestation ($latest)"
        exit 1
    fi
    echo "PASS test_clean_tree_passes"
}

# Test 2: when gates fail (synthetic minimal repo), sweep reports failure + exits 1 in strict
test_gate_failure_propagates() {
    local fixture
    fixture=$(mktemp -d)
    cd "$fixture"
    mkdir -p constitution scripts
    echo "# CLAUDE.md" > CLAUDE.md
    cp "$SWEEP" scripts/
    LAVA_REPO_ROOT="$fixture" bash scripts/verify-all-constitution-rules.sh --json-only > /dev/null 2>&1
    local rc=$?
    local latest
    latest=$(ls -t "$fixture"/.lava-ci-evidence/verify-all/*.json 2>/dev/null | head -1)
    if [[ "$rc" -eq 0 ]]; then
        rm -rf "$fixture"
        echo "FAIL test_gate_failure_propagates: expected non-zero exit when gates fail, got 0"
        exit 1
    fi
    if [[ -z "$latest" ]] || ! grep -q '"all_passed": false' "$latest"; then
        rm -rf "$fixture"
        echo "FAIL test_gate_failure_propagates: expected all_passed=false in attestation"
        exit 1
    fi
    rm -rf "$fixture"
    echo "PASS test_gate_failure_propagates"
}

# Test 3: --advisory mode exits 0 even with failures
test_advisory_mode_returns_zero() {
    local fixture
    fixture=$(mktemp -d)
    cd "$fixture"
    mkdir -p constitution scripts
    echo "# CLAUDE.md" > CLAUDE.md
    cp "$SWEEP" scripts/
    LAVA_REPO_ROOT="$fixture" bash scripts/verify-all-constitution-rules.sh --advisory --json-only > /dev/null 2>&1
    local rc=$?
    rm -rf "$fixture"
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_advisory_mode_returns_zero: --advisory should exit 0, got $rc"
        exit 1
    fi
    echo "PASS test_advisory_mode_returns_zero"
}

# Test 4: attestation JSON is structurally valid + contains gate list
test_attestation_json_structure() {
    cd "$REPO_ROOT"
    bash "$SWEEP" --json-only > /dev/null 2>&1 || true
    local latest
    latest=$(ls -t .lava-ci-evidence/verify-all/*.json 2>/dev/null | head -1)
    if [[ -z "$latest" ]]; then
        echo "FAIL test_attestation_json_structure: no attestation file produced"
        exit 1
    fi
    for field in sweep_timestamp sweep_mode sweep_constitution_pin total_gates pass_count fail_count all_passed gates; do
        if ! grep -q "\"$field\"" "$latest"; then
            echo "FAIL test_attestation_json_structure: missing field '$field' in $latest"
            exit 1
        fi
    done
    if ! grep -q '"name":' "$latest"; then
        echo "FAIL test_attestation_json_structure: no 'name' field in gates array"
        exit 1
    fi
    echo "PASS test_attestation_json_structure"
}

test_clean_tree_passes
test_gate_failure_propagates
test_advisory_mode_returns_zero
test_attestation_json_structure
echo "all verify-all-rules tests passed"
