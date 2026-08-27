#!/usr/bin/env bash
# Tests for scripts/check-script-docs-sync.sh — CM-SCRIPT-DOCS-SYNC /
# HelixConstitution §11.4.18.
#
# Falsifiability rehearsal per §6.J clause 2: each fixture deliberately
# creates / removes a drift condition, then asserts the gate fires the
# right exit code + message.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-script-docs-sync.sh"

if [[ ! -x "$SCANNER" ]]; then
    echo "FAIL: scanner not executable at $SCANNER"
    exit 1
fi

# Helper: scaffold a synthetic repo with scripts/ + docs/scripts/
scaffold() {
    local f="$1"
    mkdir -p "$f/scripts" "$f/docs/scripts"
}

# -----------------------------------------------------------------------------
# Test 1: clean fixture (1:1 mapping) → exit 0
# -----------------------------------------------------------------------------
test_clean_1to1_passes() {
    local f
    f=$(mktemp -d)
    scaffold "$f"
    cat > "$f/scripts/foo.sh" <<'EOF'
#!/usr/bin/env bash
echo foo
EOF
    cat > "$f/scripts/bar.sh" <<'EOF'
#!/usr/bin/env bash
echo bar
EOF
    cat > "$f/docs/scripts/foo.sh.md" <<'EOF'
# foo.sh user guide
EOF
    cat > "$f/docs/scripts/bar.sh.md" <<'EOF'
# bar.sh user guide
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_clean_1to1_passes"
    else
        echo "FAIL test_clean_1to1_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 2: orphan script (no doc) → reject with that script listed
# -----------------------------------------------------------------------------
test_orphan_script_rejected() {
    local f
    f=$(mktemp -d)
    scaffold "$f"
    cat > "$f/scripts/lonely.sh" <<'EOF'
#!/usr/bin/env bash
echo lonely
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "scripts/lonely.sh"; then
        echo "PASS test_orphan_script_rejected"
    else
        echo "FAIL test_orphan_script_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 3: orphan doc (no script) → reject with that doc listed
# -----------------------------------------------------------------------------
test_orphan_doc_rejected() {
    local f
    f=$(mktemp -d)
    scaffold "$f"
    cat > "$f/docs/scripts/ghost.sh.md" <<'EOF'
# Ghost: doc for a deleted script
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "docs/scripts/ghost.sh.md"; then
        echo "PASS test_orphan_doc_rejected"
    else
        echo "FAIL test_orphan_doc_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 4: both orphan types in same fixture
# -----------------------------------------------------------------------------
test_both_orphan_types_rejected() {
    local f
    f=$(mktemp -d)
    scaffold "$f"
    cat > "$f/scripts/script-only.sh" <<'EOF'
#!/usr/bin/env bash
EOF
    cat > "$f/docs/scripts/doc-only.sh.md" <<'EOF'
# doc-only
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "scripts/script-only.sh" && echo "$out" | grep -q "docs/scripts/doc-only.sh.md"; then
        echo "PASS test_both_orphan_types_rejected"
    else
        echo "FAIL test_both_orphan_types_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 5: empty scaffold (no scripts, no docs) → REFUSED.
#
# INVERTED 2026-08-26 (LVA vacuous-pass sweep F12). This case previously asserted
# exit 0 and its own comment named the reason: "pass (vacuously 0:0)". That is
# the defect, not the contract. `0 scripts ↔ 0 docs (1:1)` satisfies the 1:1
# invariant perfectly while having compared nothing — "nothing was learned"
# reported as "nothing failed", which is the shape §6.J forbids and which the
# clause-6.H credential floor (check-constitution.sh:188) and the verify-all
# registry floor (verify-all-constitution-rules.sh:290) already guard against
# elsewhere in this tree.
#
# The gate now refuses an empty corpus and derives its expectation from the git
# index. This test asserts the refusal AND that the message is actionable — a
# `set -e` abort with no output is also non-zero, and would not be acceptable.
test_empty_scaffold_refused() {
    local f
    f=$(mktemp -d)
    scaffold "$f"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -ne 0 ]] && grep -q 'compared ZERO scripts against ZERO docs' <<<"$out"; then
        echo "PASS test_empty_scaffold_refused"
    else
        echo "FAIL test_empty_scaffold_refused: expected a refusal naming the empty corpus, rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 6: missing scripts/ or docs/scripts/ directory → REFUSED.
#
# INVERTED 2026-08-26 (LVA vacuous-pass sweep F12), same reasoning as Test 5.
# "skipping — scripts/ or docs/scripts/ missing" followed by exit 0 reports a
# clean 1:1 sync that was never checked. A skip is not a pass.
test_missing_directories_refused() {
    local f
    f=$(mktemp -d)
    # Don't scaffold — just an empty repo root
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -ne 0 ]] && grep -q 'a corpus directory is ABSENT' <<<"$out"; then
        echo "PASS test_missing_directories_refused"
    else
        echo "FAIL test_missing_directories_refused: expected a refusal naming the absent directory, rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 7: real repo sanity check
# -----------------------------------------------------------------------------
test_real_repo_passes() {
    local out rc
    out=$(bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_real_repo_passes"
    else
        echo "FAIL test_real_repo_passes: rc=$rc out=$out"
        exit 1
    fi
}

test_clean_1to1_passes
test_orphan_script_rejected
test_orphan_doc_rejected
test_both_orphan_types_rejected
test_empty_scaffold_refused
test_missing_directories_refused
test_real_repo_passes

echo "All 7 script-docs-sync gate tests PASSED"
