#!/usr/bin/env bash
# Tests for scripts/audit-snake_case-references.sh — §11.4.29 rename-blast-radius audit.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/audit-snake_case-references.sh"

PASS_COUNT=0
FAIL_COUNT=0

pass() { echo "PASS $1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { echo "FAIL $1: $2"; FAIL_COUNT=$((FAIL_COUNT + 1)); }

# Test 1: script exists and is executable
test_script_executable() {
    if [[ -x "$SCRIPT" ]]; then
        pass "test_script_executable"
    else
        fail "test_script_executable" "script not executable: $SCRIPT"
    fi
}

# Test 2: script runs cleanly on the real repo tree + emits the expected TSV header
test_runs_cleanly_on_real_tree() {
    local out rc
    out=$(bash "$SCRIPT" 2>&1)
    rc=$?
    if [[ $rc -ne 0 ]]; then
        fail "test_runs_cleanly_on_real_tree" "script exited rc=$rc; output=$out"
        return
    fi
    if ! echo "$out" | head -1 | grep -qE '^NAME[[:space:]]+REFS[[:space:]]+FILES$'; then
        fail "test_runs_cleanly_on_real_tree" "expected TSV header on line 1; got: $(echo "$out" | head -1)"
        return
    fi
    pass "test_runs_cleanly_on_real_tree"
}

# Test 3: script reports all 17 expected submodules in its output
test_reports_all_17_submodules() {
    local out
    out=$(bash "$SCRIPT" 2>&1)
    local missing=()
    for name in Auth Cache Challenges Concurrency Config Containers Database \
                Discovery HelixQA HTTP3 Mdns Middleware Observability \
                RateLimiter Recovery Security Tracker-SDK; do
        if ! echo "$out" | grep -qE "^${name}[[:space:]]"; then
            missing+=("$name")
        fi
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
        pass "test_reports_all_17_submodules"
    else
        fail "test_reports_all_17_submodules" "missing names: ${missing[*]}"
    fi
}

# Test 4: script reports the TOTAL_Submodules aggregate line
test_reports_total_line() {
    local out
    out=$(bash "$SCRIPT" 2>&1)
    if echo "$out" | grep -qE '^TOTAL_Submodules[[:space:]]+[0-9]+[[:space:]]+[0-9]+$'; then
        pass "test_reports_total_line"
    else
        fail "test_reports_total_line" "TOTAL_Submodules line missing or malformed"
    fi
}

# Test 5: --format=md emits a Markdown table header
test_format_md() {
    local out
    out=$(bash "$SCRIPT" --format=md 2>&1)
    if echo "$out" | head -1 | grep -qE '^\| Name \| Refs \| Files \|$'; then
        pass "test_format_md"
    else
        fail "test_format_md" "expected Markdown header on line 1; got: $(echo "$out" | head -1)"
    fi
}

# Test 6: --names-only emits the same shape
test_names_only_mode() {
    local out
    out=$(bash "$SCRIPT" --names-only 2>&1)
    if echo "$out" | head -1 | grep -qE '^NAME[[:space:]]+REFS[[:space:]]+FILES$'; then
        # Count: 1 header + 17 names = 18 lines (no TOTAL line in names-only)
        local line_count
        line_count=$(echo "$out" | wc -l | awk '{print $1+0}')
        if [[ "$line_count" -eq 18 ]]; then
            pass "test_names_only_mode"
        else
            fail "test_names_only_mode" "expected 18 lines (1 header + 17 names); got $line_count"
        fi
    else
        fail "test_names_only_mode" "expected TSV header"
    fi
}

# Test 7: --help exits 0 and prints docs
test_help_exits_zero() {
    local out rc
    out=$(bash "$SCRIPT" --help 2>&1)
    rc=$?
    if [[ $rc -eq 0 ]] && echo "$out" | grep -q "Purpose:"; then
        pass "test_help_exits_zero"
    else
        fail "test_help_exits_zero" "rc=$rc, expected --help to exit 0 and print docs"
    fi
}

# Test 8: unknown argument exits 2
test_unknown_arg_exits_2() {
    local out rc
    out=$(bash "$SCRIPT" --bogus-flag 2>&1)
    rc=$?
    if [[ $rc -eq 2 ]] && echo "$out" | grep -q "unknown argument"; then
        pass "test_unknown_arg_exits_2"
    else
        fail "test_unknown_arg_exits_2" "rc=$rc, expected exit 2 + error msg"
    fi
}

# Test 9: output is deterministic (running twice yields identical TSV)
test_deterministic_output() {
    local a b
    a=$(bash "$SCRIPT" 2>&1)
    b=$(bash "$SCRIPT" 2>&1)
    if [[ "$a" == "$b" ]]; then
        pass "test_deterministic_output"
    else
        fail "test_deterministic_output" "two consecutive runs produced different output"
    fi
}

# Test 10: numeric columns are numbers
test_numeric_columns() {
    local out
    out=$(bash "$SCRIPT" 2>&1 | tail -n +2)
    local malformed
    malformed=$(echo "$out" | awk -F'\t' '$2 !~ /^[0-9]+$/ || $3 !~ /^[0-9]+$/ {print}')
    if [[ -z "$malformed" ]]; then
        pass "test_numeric_columns"
    else
        fail "test_numeric_columns" "non-numeric values found: $malformed"
    fi
}

# Test 11: counts are non-negative + Containers > Discovery (sanity baseline)
# This is an anti-bluff guarantee: the script must produce a count that
# reflects the actual repo state, not a hardcoded value. Containers has
# vastly more references than Discovery (370 vs 5 at time of writing) —
# if the script were lying about its inputs, this invariant would break.
test_blast_radius_ordering_sanity() {
    local out containers_refs discovery_refs
    out=$(bash "$SCRIPT" 2>&1)
    containers_refs=$(echo "$out" | awk '/^Containers\t/ {print $2}')
    discovery_refs=$(echo "$out" | awk '/^Discovery\t/ {print $2}')
    if [[ -n "$containers_refs" && -n "$discovery_refs" \
          && "$containers_refs" -gt "$discovery_refs" ]]; then
        pass "test_blast_radius_ordering_sanity"
    else
        fail "test_blast_radius_ordering_sanity" \
             "Containers ($containers_refs) should exceed Discovery ($discovery_refs)"
    fi
}

# Run all tests
test_script_executable
test_runs_cleanly_on_real_tree
test_reports_all_17_submodules
test_reports_total_line
test_format_md
test_names_only_mode
test_help_exits_zero
test_unknown_arg_exits_2
test_deterministic_output
test_numeric_columns
test_blast_radius_ordering_sanity

echo ""
echo "Results: $PASS_COUNT passed, $FAIL_COUNT failed"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
    exit 1
fi
exit 0
