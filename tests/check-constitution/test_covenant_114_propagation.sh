#!/usr/bin/env bash
# Tests for the CM-COVENANT-114-*-PROPAGATION gate in
# scripts/check-constitution.sh — §6.AI-debt item 4 / §11.4.128–§11.4.141.
#
# The gate asserts the UNIVERSAL clause anchors §6.AI adopts remain present
# as literal "11.4.N" tokens in root CLAUDE.md, so the constitution
# submodule's CM-COVENANT-114-* scans can confirm adoption (not silent drop).
#
# Falsifiability rehearsal per §6.J clause 2: the negative test physically
# removes one anchor literal from a COPY-protected real CLAUDE.md, runs the
# REAL production gate (scripts/check-constitution.sh — not a re-implementation),
# and asserts the gate fires with a CM-COVENANT-114 violation message. The
# original CLAUDE.md is restored by a trap regardless of outcome.
#
# Scope note: §11.4.135–§11.4.139 are the ATMosphere-TV-specific batch §6.AI
# demotes as NOT binding on Lava — they are deliberately NOT gated, so this
# test does NOT assert their presence.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="$REPO_ROOT/scripts/check-constitution.sh"
CLAUDE_MD="$REPO_ROOT/CLAUDE.md"

if [[ ! -x "$CHECKER" ]]; then
    echo "FAIL: checker not executable at $CHECKER"
    exit 1
fi
if [[ ! -f "$CLAUDE_MD" ]]; then
    echo "FAIL: CLAUDE.md not found at $CLAUDE_MD"
    exit 1
fi

# The 9 universal anchors the gate enforces (mirror the gate's own list).
ANCHORS=(11.4.128 11.4.129 11.4.130 11.4.131 11.4.132 11.4.133 11.4.134 11.4.140 11.4.141)

# -----------------------------------------------------------------------------
# Test 1 (positive): every universal anchor is present as a literal token.
# This is a structural assertion that does NOT depend on the rest of the
# checker passing, so it is robust even if an unrelated gate is red in CI.
# -----------------------------------------------------------------------------
test_anchors_present_in_claude_md() {
    local missing=()
    local a
    for a in "${ANCHORS[@]}"; do
        if ! grep -qF "$a" "$CLAUDE_MD"; then
            missing+=("$a")
        fi
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
        echo "PASS test_anchors_present_in_claude_md"
    else
        echo "FAIL test_anchors_present_in_claude_md: missing ${missing[*]}"
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# Test 2 (positive): the §6.AI + §6.AJ clause headings are present.
# -----------------------------------------------------------------------------
test_clauses_present() {
    if grep -qF '##### 6.AI — HelixConstitution §11.4.128' "$CLAUDE_MD" \
        && grep -qF '##### 6.AJ — Universal Action-Prefix Recognition' "$CLAUDE_MD"; then
        echo "PASS test_clauses_present"
    else
        echo "FAIL test_clauses_present: §6.AI or §6.AJ heading missing"
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# Test 3 (falsifiability): remove one anchor literal from the real CLAUDE.md,
# run the REAL gate, assert it fails with a CM-COVENANT-114 violation, restore.
# This exercises the exact production code path (no re-implementation).
# -----------------------------------------------------------------------------
test_removed_anchor_makes_gate_fire() {
    local backup
    backup=$(mktemp)
    cp "$CLAUDE_MD" "$backup"
    # Guarantee restoration even if the test aborts/errors.
    trap 'cp "$backup" "$CLAUDE_MD"; rm -f "$backup"' RETURN

    # Mutate: delete EVERY occurrence of the 11.4.134 literal token so no
    # form of the anchor survives. 134 is chosen because it appears exactly
    # once (count=1) so the mutation is unambiguous; the gate must then fire.
    # Use a portable in-place edit (sed -i differs across BSD/GNU): write to
    # a temp then move back.
    local mutated
    mutated=$(mktemp)
    sed 's/11\.4\.134/REDACTED_ANCHOR/g' "$CLAUDE_MD" > "$mutated"
    cp "$mutated" "$CLAUDE_MD"
    rm -f "$mutated"

    # Sanity: confirm the mutation actually removed the literal.
    if grep -qF "11.4.134" "$CLAUDE_MD"; then
        echo "FAIL test_removed_anchor_makes_gate_fire: mutation did not remove 11.4.134"
        return 1
    fi

    local out rc
    out=$(bash "$CHECKER" 2>&1)
    rc=$?

    # The gate MUST have fired: non-zero exit AND a CM-COVENANT-114 message
    # naming the missing 11.4.134 anchor. (If an unrelated earlier gate failed
    # first, the CM-COVENANT-114 message would be absent → this correctly
    # reports a non-PASS rather than a false green.)
    if [[ "$rc" -ne 0 ]] && echo "$out" | grep -q "CM-COVENANT-114 VIOLATION" \
        && echo "$out" | grep -qF "11.4.134"; then
        echo "PASS test_removed_anchor_makes_gate_fire"
        echo "  observed gate failure message:"
        echo "$out" | grep -E "CM-COVENANT-114|11.4.134" | sed 's/^/    /'
    else
        echo "FAIL test_removed_anchor_makes_gate_fire: gate did NOT fire as expected"
        echo "  rc=$rc"
        echo "  out=$out"
        return 1
    fi
}

# -----------------------------------------------------------------------------
# Test 4 (positive end-to-end): with the real, unmutated CLAUDE.md the whole
# checker exits 0 (the anchors ARE present). This is the live proof the gate
# is satisfied in the current tree.
# -----------------------------------------------------------------------------
test_real_checker_passes() {
    local out rc
    out=$(bash "$CHECKER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_real_checker_passes"
    else
        echo "FAIL test_real_checker_passes: checker exited $rc"
        echo "$out"
        exit 1
    fi
}

test_anchors_present_in_claude_md
test_clauses_present
test_removed_anchor_makes_gate_fire || exit 1
test_real_checker_passes

echo "All 4 CM-COVENANT-114-*-PROPAGATION gate tests PASSED"
