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
# Test 5: empty scaffold (no scripts, no docs) → pass (vacuously 0:0)
# -----------------------------------------------------------------------------
test_empty_scaffold_passes() {
    local f
    f=$(mktemp -d)
    scaffold "$f"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_empty_scaffold_passes"
    else
        echo "FAIL test_empty_scaffold_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 6: missing scripts/ or docs/scripts/ directory → skip (exit 0)
# -----------------------------------------------------------------------------
test_missing_directories_skip() {
    local f
    f=$(mktemp -d)
    # Don't scaffold — just an empty repo root
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "skipping"; then
        echo "PASS test_missing_directories_skip"
    else
        echo "FAIL test_missing_directories_skip: rc=$rc out=$out"
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
test_empty_scaffold_passes
test_missing_directories_skip
test_real_repo_passes

echo "All 7 script-docs-sync gate tests PASSED"
