#!/usr/bin/env bash
# Tests for the hermetic-suite registration guards in
# scripts/verify-all-constitution-rules.sh (added 2026-08-22 by the §6.N.2
# gate-shaping bluff hunt).
#
# The defect these guard: the sweep registered its hermetic suites
# conditionally —
#     if [[ -x "$suite/run_all.sh" ]]; then run_gate ...; fi
#     for t in tests/pre-push/check*_test.sh; do [[ -f "$t" ]] || continue; ...
# so a suite that was deleted, renamed, or had merely lost its exec bit
# DISAPPEARED from the gate registry. The sweep then reported
# "N PASS / 0 FAIL" and wrote "all_passed": true with no indication that N had
# shrunk — coverage loss rendered as a clean verdict, which is §11.4.32's own
# stated violation ("A sweep that exits PASS without running every
# implementable gate is a §11.4.32 violation") and §6.J's "zero items examined,
# therefore PASS".
#
# Falsifiability rehearsal per §6.J clause 2: test 1 removes the suites and
# asserts each one now appears as a FAIL ROW rather than vanishing; test 2 is
# the positive control proving a present, passing suite still yields PASS, so
# the guard cannot be mistaken for an unconditional failure.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWEEP="$REPO_ROOT/scripts/verify-all-constitution-rules.sh"

if [[ ! -f "$SWEEP" ]]; then
    echo "FAIL: sweep not found at $SWEEP"
    exit 1
fi

_latest_attestation() {
    ls -1t "$1/.lava-ci-evidence/verify-all"/*.json 2>/dev/null | head -1
}

# -----------------------------------------------------------------------------
# Test 1: suites absent → registered as FAIL rows, not silently dropped
# -----------------------------------------------------------------------------
test_absent_suites_register_as_fail() {
    local d att rc out
    d=$(mktemp -d)
    mkdir -p "$d/tests/pre-push" "$d/tests/check-constitution"
    out=$(LAVA_REPO_ROOT="$d" bash "$SWEEP" --json-only 2>&1)
    rc=$?
    att=$(_latest_attestation "$d")
    if [[ -z "$att" ]]; then
        echo "FAIL test_absent_suites_register_as_fail: no attestation written (rc=$rc, out=$out)"
        rm -rf "$d"; exit 1
    fi
    local missing=()
    for row in hermetic-suite-firebase hermetic-suite-vm-distro \
               hermetic-pre-push-suite-present hermetic-check-constitution-suite-present; do
        grep -qF "\"name\": \"$row\"" "$att" || missing+=("$row")
    done
    if [[ "$rc" -ne 0 ]] && [[ ${#missing[@]} -eq 0 ]] \
       && grep -qF '"all_passed": false' "$att"; then
        echo "PASS test_absent_suites_register_as_fail"
    else
        echo "FAIL test_absent_suites_register_as_fail: rc=$rc missing_rows=[${missing[*]:-}] att=$att"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 2: a present, executable, passing suite yields a PASS row
# -----------------------------------------------------------------------------
test_present_suite_registers_as_pass() {
    local d att
    d=$(mktemp -d)
    mkdir -p "$d/tests/firebase" "$d/tests/pre-push" "$d/tests/check-constitution"
    printf '#!/usr/bin/env bash\nexit 0\n' > "$d/tests/firebase/run_all.sh"
    chmod +x "$d/tests/firebase/run_all.sh"
    LAVA_REPO_ROOT="$d" bash "$SWEEP" --json-only >/dev/null 2>&1
    att=$(_latest_attestation "$d")
    if [[ -n "$att" ]] && grep -qF '"name": "hermetic-suite-firebase", "rule_ref": "§11.4 anti-bluff hermetic test suite", "result": "PASS"' "$att"; then
        echo "PASS test_present_suite_registers_as_pass"
    else
        echo "FAIL test_present_suite_registers_as_pass: att=$att row=$(grep -F 'hermetic-suite-firebase' "$att" 2>/dev/null)"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 3: the sweep refuses to emit a verdict from an empty gate registry
# (defence-in-depth floor; unreachable while the ~20 unconditional gates
# remain, so it is asserted on the extracted verdict block).
# -----------------------------------------------------------------------------
test_zero_gate_floor_present() {
    if grep -qF 'the sweep registered ZERO gates' "$SWEEP" \
       && grep -qE 'if \[\[ "\$total_gates" -eq 0 \]\]' "$SWEEP"; then
        echo "PASS test_zero_gate_floor_present"
    else
        echo "FAIL test_zero_gate_floor_present: floor missing from $SWEEP"
        exit 1
    fi
}

test_absent_suites_register_as_fail
test_present_suite_registers_as_pass
test_zero_gate_floor_present

echo "All 3 verify-all suite-registration tests PASSED"
