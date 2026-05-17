#!/usr/bin/env bash
# Tests for scripts/check-no-guessing-vocabulary.sh — §6.AD.6 / §11.4.6 gate.
#
# Falsifiability rehearsal per §6.J clause 2: each test fixture deliberately
# breaks the gate's expected outcome (e.g., forbidden word with no
# whitelist), then asserts the gate fires the right exit code + message.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-no-guessing-vocabulary.sh"

if [[ ! -x "$SCANNER" ]]; then
    echo "FAIL: scanner not executable at $SCANNER"
    exit 1
fi

# -----------------------------------------------------------------------------
# Test 1: clean fixture (no forbidden words) → exit 0
# -----------------------------------------------------------------------------
test_clean_fixture_passes() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/incidents"
    cat > "$f/incidents/clean.md" <<'EOF'
# Clean Incident

Root cause CONFIRMED: a race condition between two coroutines reading
the same SharedFlow. Stack trace captured at line 42.
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" LAVA_NO_GUESSING_SCAN_PATHS="incidents" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_clean_fixture_passes"
    else
        echo "FAIL test_clean_fixture_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 2: forbidden word without whitelist → exit 1 (gate fires)
# -----------------------------------------------------------------------------
test_forbidden_word_rejected() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/incidents"
    cat > "$f/incidents/bad.md" <<'EOF'
# Bad Incident

The root cause probably involves a race condition between two coroutines.
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" LAVA_NO_GUESSING_SCAN_PATHS="incidents" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "VIOLATION"; then
        echo "PASS test_forbidden_word_rejected"
    else
        echo "FAIL test_forbidden_word_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 3: UNCONFIRMED: whitelist allows the otherwise-forbidden word
# -----------------------------------------------------------------------------
test_unconfirmed_whitelist_passes() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/incidents"
    cat > "$f/incidents/whitelisted.md" <<'EOF'
# Whitelisted Incident

UNCONFIRMED: the root cause might involve a race condition; needs
forensic capture before downgrade to CONFIRMED.
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" LAVA_NO_GUESSING_SCAN_PATHS="incidents" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_unconfirmed_whitelist_passes"
    else
        echo "FAIL test_unconfirmed_whitelist_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 4: PENDING_FORENSICS: whitelist also passes
# -----------------------------------------------------------------------------
test_pending_forensics_whitelist_passes() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/incidents"
    cat > "$f/incidents/pending.md" <<'EOF'
# Pending Incident

PENDING_FORENSICS: possibly a memory leak in the SDK consumer; capture
needed via adb shell dumpsys meminfo across 3 cold-launches.
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" LAVA_NO_GUESSING_SCAN_PATHS="incidents" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_pending_forensics_whitelist_passes"
    else
        echo "FAIL test_pending_forensics_whitelist_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 5: forensic-anchor verbatim-quote exemption passes
# (covers the "verbatim agent output" exemption in the scanner)
# -----------------------------------------------------------------------------
test_verbatim_quote_exemption_passes() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/incidents"
    # The exemption is line-scoped: the line containing the forbidden word
    # MUST also contain "forensic anchor" / "verbatim operator|agent|user" /
    # "historical quote" markers. This mirrors how real forensic-anchor docs
    # cite verbatim operator output inline (e.g., "operator verbatim message:
    # 'the build probably hung'").
    cat > "$f/incidents/verbatim.md" <<'EOF'
# Verbatim Operator Output

Forensic anchor operator verbatim quote 2026-05-17: "the build probably hung because the daemon got stuck" — historical quote, NOT a current cause-assertion.
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" LAVA_NO_GUESSING_SCAN_PATHS="incidents" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_verbatim_quote_exemption_passes"
    else
        echo "FAIL test_verbatim_quote_exemption_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 6: multiple scan paths via colon separator
# -----------------------------------------------------------------------------
test_multiple_scan_paths() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/incidents" "$f/resolved"
    cat > "$f/incidents/clean.md" <<'EOF'
CONFIRMED: race fixed by mutex.
EOF
    cat > "$f/resolved/bad.md" <<'EOF'
The fix presumably resolved the bug.
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" LAVA_NO_GUESSING_SCAN_PATHS="incidents:resolved" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "resolved/bad.md"; then
        echo "PASS test_multiple_scan_paths"
    else
        echo "FAIL test_multiple_scan_paths: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 7: real repo runs clean (sanity check)
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

test_clean_fixture_passes
test_forbidden_word_rejected
test_unconfirmed_whitelist_passes
test_pending_forensics_whitelist_passes
test_verbatim_quote_exemption_passes
test_multiple_scan_paths
test_real_repo_passes

echo "All 7 no-guessing-vocabulary gate tests PASSED"
