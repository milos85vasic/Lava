#!/usr/bin/env bash
# Tests for the gate-execution isolation of
# scripts/verify-all-constitution-rules.sh (§11.4.32 enforcement engine).
#
# §6.N.2 gate-shaping bluff hunt (2026-08-23). run_gate executes each gate's
# command string with `eval`. If that eval runs in the SWEEP'S OWN SHELL, a
# gate command that reaches `exit` terminates the entire sweep instead of
# failing one gate: every later gate is silently skipped, the already-recorded
# FAIL rows are discarded, no attestation JSON is written, and — when the gate
# exits 0 — the sweep itself exits 0. That is a clean green verdict from a
# sweep that stopped part-way, which §11.4.32 names explicitly: "A sweep that
# exits PASS without running every implementable gate is a §11.4.32 violation."
#
# These tests drive the REAL run_gate implementation, extracted verbatim from
# the sweep script at run time (never re-implemented here), so they keep
# tracking the shipped code.
#
# Inheritance: §11.4.32; §6.J/§6.L/§6.N.2 (anti-bluff).
# Classification: project-specific (the sweep is Lava's; the isolate-your-gate-
# execution discipline is universal).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWEEP="$REPO_ROOT/scripts/verify-all-constitution-rules.sh"

# Extract the array declarations + the run_gate function verbatim from the
# shipped sweep. Anchored on the `declare -a GATE_NAMES` line through the
# first column-0 `}` after `run_gate() {`.
extract_run_gate() {
    awk '/^declare -a GATE_NAMES=/{inblock=1} inblock{print} inblock && /^}$/{exit}' "$SWEEP"
}

# Test 1: a gate whose command reaches `exit 0` must NOT terminate the sweep.
test_exiting_gate_does_not_abort_sweep() {
    local fixture rc out
    fixture=$(mktemp -d)
    extract_run_gate > "$fixture/run_gate.inc"

    if ! grep -q 'run_gate()' "$fixture/run_gate.inc"; then
        rm -rf "$fixture"
        echo "FAIL test_exiting_gate_does_not_abort_sweep: could not extract run_gate from $SWEEP"
        exit 1
    fi

    cat > "$fixture/harness.sh" <<'EOF'
set -uo pipefail
JSON_ONLY=1
source ./run_gate.inc
run_gate "gate-A-fails"    "ref" "false"
run_gate "gate-B-exits"    "ref" "exit 0"
run_gate "gate-C-must-run" "ref" "true"
printf 'REGISTERED=%s RESULTS=%s\n' "${#GATE_NAMES[@]}" "${GATE_RESULTS[*]}"
EOF

    out=$(cd "$fixture" && bash harness.sh 2>&1)
    rc=$?
    rm -rf "$fixture"

    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_exiting_gate_does_not_abort_sweep: harness exited $rc"
        echo "  output: $out"
        exit 1
    fi
    if [[ "$out" != *"REGISTERED=3"* ]]; then
        echo "FAIL test_exiting_gate_does_not_abort_sweep: a gate command that calls"
        echo "  'exit' aborted the sweep's own shell — gates after it never ran and the"
        echo "  recorded results were discarded (§11.4.32: a sweep must run every gate)."
        echo "  expected REGISTERED=3, got: ${out:-<no output — shell terminated>}"
        exit 1
    fi
    if [[ "$out" != *"RESULTS=FAIL PASS PASS"* ]]; then
        echo "FAIL test_exiting_gate_does_not_abort_sweep: expected 'RESULTS=FAIL PASS PASS'"
        echo "  (gate-A false -> FAIL, gate-B 'exit 0' -> PASS, gate-C true -> PASS)"
        echo "  got: $out"
        exit 1
    fi
    echo "PASS test_exiting_gate_does_not_abort_sweep"
}

# Test 2: a gate that exits NON-zero must be recorded as FAIL, and later gates
# must still run. This is the shape the sweep's own guard rows would have hit
# had they used `exit 1` instead of `false`.
test_nonzero_exiting_gate_records_fail() {
    local fixture rc out
    fixture=$(mktemp -d)
    extract_run_gate > "$fixture/run_gate.inc"

    cat > "$fixture/harness.sh" <<'EOF'
set -uo pipefail
JSON_ONLY=1
source ./run_gate.inc
run_gate "gate-A-exits-1"  "ref" "echo boom >&2; exit 1"
run_gate "gate-B-must-run" "ref" "true"
printf 'REGISTERED=%s RESULTS=%s\n' "${#GATE_NAMES[@]}" "${GATE_RESULTS[*]}"
EOF

    out=$(cd "$fixture" && bash harness.sh 2>&1)
    rc=$?
    rm -rf "$fixture"

    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_nonzero_exiting_gate_records_fail: harness exited $rc; output: $out"
        exit 1
    fi
    if [[ "$out" != *"REGISTERED=2"* || "$out" != *"RESULTS=FAIL PASS"* ]]; then
        echo "FAIL test_nonzero_exiting_gate_records_fail: expected REGISTERED=2 and"
        echo "  'RESULTS=FAIL PASS'; got: ${out:-<no output — shell terminated>}"
        exit 1
    fi
    echo "PASS test_nonzero_exiting_gate_records_fail"
}

# Test 3: a gate command must not be able to mutate the sweep's own bookkeeping
# (gate isolation). A gate that clobbers GATE_RESULTS would rewrite the verdict.
test_gate_cannot_mutate_sweep_state() {
    local fixture rc out
    fixture=$(mktemp -d)
    extract_run_gate > "$fixture/run_gate.inc"

    cat > "$fixture/harness.sh" <<'EOF'
set -uo pipefail
JSON_ONLY=1
source ./run_gate.inc
run_gate "gate-A-fails"     "ref" "false"
run_gate "gate-B-clobbers"  "ref" "GATE_RESULTS=(PASS); true"
printf 'RESULTS=%s\n' "${GATE_RESULTS[*]}"
EOF

    out=$(cd "$fixture" && bash harness.sh 2>&1)
    rc=$?
    rm -rf "$fixture"

    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_gate_cannot_mutate_sweep_state: harness exited $rc; output: $out"
        exit 1
    fi
    if [[ "$out" != *"RESULTS=FAIL PASS"* ]]; then
        echo "FAIL test_gate_cannot_mutate_sweep_state: a gate command rewrote the"
        echo "  sweep's GATE_RESULTS array, so the recorded FAIL was erased from the"
        echo "  verdict. expected 'RESULTS=FAIL PASS', got: $out"
        exit 1
    fi
    echo "PASS test_gate_cannot_mutate_sweep_state"
}

test_exiting_gate_does_not_abort_sweep
test_nonzero_exiting_gate_records_fail
test_gate_cannot_mutate_sweep_state
echo "all verify-all gate-isolation tests passed"
