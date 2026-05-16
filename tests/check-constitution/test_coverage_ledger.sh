#!/usr/bin/env bash
# Tests for scripts/check-coverage-ledger.sh + scripts/generate-coverage-ledger.sh
# (§11.4.25 Full-Automation-Coverage ledger gate).
#
# Each test sets LAVA_REPO_ROOT to a synthetic fixture directory + drives
# the scanner against it. Discrimination tests deliberately mutate the
# fixture to assert the scanner FAILs — that's the falsifiability
# rehearsal §6.J/§6.L prescribes.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATOR="$REPO_ROOT/scripts/generate-coverage-ledger.sh"
VERIFIER="$REPO_ROOT/scripts/check-coverage-ledger.sh"

# Build a minimal compliant fixture: 1 feature module + 1 core module +
# app/ + lava-api-go + submodules/, with a generated docs/coverage-ledger.yaml
# under that root.
make_fixture() {
    local root="$1"
    mkdir -p "$root/feature/foo" "$root/core/bar" "$root/app" \
             "$root/lava-api-go" "$root/submodules/auth" "$root/docs"
    # Add a token file so directories aren't empty (find -type d ignores
    # empty dir contents but git would).
    touch "$root/feature/foo/build.gradle.kts"
    touch "$root/core/bar/build.gradle.kts"
    touch "$root/app/build.gradle.kts"
    touch "$root/lava-api-go/go.mod"
    touch "$root/submodules/auth/README.md"
}

# Generate a ledger inside the fixture root.
generate_ledger_in() {
    local root="$1"
    LAVA_REPO_ROOT="$root" bash "$GENERATOR" --quiet 2>/dev/null
}

# Run the verifier against a fixture root.
verify_in() {
    local root="$1"
    shift
    LAVA_REPO_ROOT="$root" bash "$VERIFIER" "$@" 2>&1
}

# -----------------------------------------------------------------------------
# Test 1: clean fixture with freshly-generated ledger PASSES
# -----------------------------------------------------------------------------
test_clean_fixture_passes() {
    local f
    f=$(mktemp -d)
    make_fixture "$f"
    generate_ledger_in "$f"
    local out rc
    out=$(verify_in "$f" --strict)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -qE "well-formed, complete, and fresh"; then
        echo "PASS test_clean_fixture_passes"
    else
        echo "FAIL test_clean_fixture_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 2: missing ledger file → REJECTED
# -----------------------------------------------------------------------------
test_missing_ledger_rejected() {
    local f
    f=$(mktemp -d)
    make_fixture "$f"
    # Do NOT generate a ledger.
    local out rc
    out=$(verify_in "$f" --strict)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "missing — regenerate"; then
        echo "PASS test_missing_ledger_rejected"
    else
        echo "FAIL test_missing_ledger_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 3: ledger missing a row for an on-disk path → REJECTED
# -----------------------------------------------------------------------------
test_missing_row_rejected() {
    local f
    f=$(mktemp -d)
    make_fixture "$f"
    generate_ledger_in "$f"
    # Add a new feature/* directory AFTER generation → ledger is now stale
    # AND missing a row for the new path.
    mkdir -p "$f/feature/newfeat"
    touch "$f/feature/newfeat/build.gradle.kts"
    local out rc
    out=$(verify_in "$f" --strict)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "missing rows for 1 on-disk paths" && echo "$out" | grep -qE "feature/newfeat"; then
        echo "PASS test_missing_row_rejected"
    else
        echo "FAIL test_missing_row_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 4: stale ledger (committed content drifts from regenerated) → REJECTED
# -----------------------------------------------------------------------------
test_stale_ledger_rejected() {
    local f
    f=$(mktemp -d)
    make_fixture "$f"
    generate_ledger_in "$f"
    # Mutate the ledger by hand: flip a status value. Regeneration would
    # produce the original value, so the diff exposes the staleness.
    # The verifier's "strip metadata" only strips metadata + generated_at —
    # status changes in row content survive the strip.
    sed -i.bak 's/status: "covered"/status: "MUTATED"/g; s/status: "partial"/status: "MUTATED"/g; s/status: "gap"/status: "MUTATED"/g' "$f/docs/coverage-ledger.yaml"
    rm -f "$f/docs/coverage-ledger.yaml.bak"
    local out rc
    out=$(verify_in "$f" --strict)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "STALE"; then
        echo "PASS test_stale_ledger_rejected"
    else
        echo "FAIL test_stale_ledger_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 5: --advisory mode swallows exit code even on violation
# -----------------------------------------------------------------------------
test_advisory_mode_returns_zero() {
    local f
    f=$(mktemp -d)
    # Empty fixture — verifier finds no ledger → would normally exit 1.
    mkdir -p "$f/feature/foo"
    touch "$f/feature/foo/build.gradle.kts"
    local rc
    LAVA_REPO_ROOT="$f" bash "$VERIFIER" --advisory >/dev/null 2>&1
    rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_advisory_mode_returns_zero"
    else
        echo "FAIL test_advisory_mode_returns_zero: expected 0 in advisory, got $rc"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 6: generator emits deterministic content (same input → same output)
#         — anti-bluff falsifiability rehearsal for the generator itself.
# -----------------------------------------------------------------------------
test_generator_deterministic() {
    local f
    f=$(mktemp -d)
    make_fixture "$f"
    local a b
    a=$(LAVA_REPO_ROOT="$f" bash "$GENERATOR" --stdout 2>/dev/null | awk '
        /^metadata:/   { in_meta = 1; next }
        in_meta && /^rows:/ { in_meta = 0; print; next }
        in_meta        { next }
        /generated_at:/{ next }
        { print }
    ')
    b=$(LAVA_REPO_ROOT="$f" bash "$GENERATOR" --stdout 2>/dev/null | awk '
        /^metadata:/   { in_meta = 1; next }
        in_meta && /^rows:/ { in_meta = 0; print; next }
        in_meta        { next }
        /generated_at:/{ next }
        { print }
    ')
    if [[ "$a" == "$b" ]]; then
        echo "PASS test_generator_deterministic"
    else
        echo "FAIL test_generator_deterministic: row content differs across two runs"
        diff <(echo "$a") <(echo "$b") | head -20
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

test_clean_fixture_passes
test_missing_ledger_rejected
test_missing_row_rejected
test_stale_ledger_rejected
test_advisory_mode_returns_zero
test_generator_deterministic
echo "all coverage-ledger tests passed"
