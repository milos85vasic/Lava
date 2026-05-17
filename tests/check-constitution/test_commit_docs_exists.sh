#!/usr/bin/env bash
# Tests for scripts/check-commit-docs-exists.sh — CM-COMMIT-DOCS-EXISTS.
#
# Each fixture creates a synthetic git repo with controlled commit
# bodies and asserts the scanner returns the expected exit code +
# orphan listing.
#
# Falsifiability rehearsal per §6.J clause 2: fixture 3 deliberately
# cites a path that does NOT exist; scanner MUST reject. Fixture 4
# protects the strikethrough whitelist; fixture 5 the backtick
# whitelist; fixture 6 the fuzzy basename fallback.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-commit-docs-exists.sh"

if [[ ! -x "$SCANNER" ]]; then
    echo "FAIL: scanner not executable at $SCANNER"
    exit 1
fi

# Helper: scaffold a temp git repo + commit a body
scaffold() {
    local dir="$1"
    git -C "$dir" init -q -b master 2>/dev/null
    git -C "$dir" config user.email "test@example.com"
    git -C "$dir" config user.name "Test"
    git -C "$dir" config commit.gpgsign false
}
commit_body() {
    local dir="$1"; local body="$2"
    git -C "$dir" commit --allow-empty -q -m "$body"
}

# -----------------------------------------------------------------------------
# Test 1: commit with no path refs → exit 0
# -----------------------------------------------------------------------------
test_no_refs_passes() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_body "$f" "trivial: refactor internal var name; no file references in this message"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_no_refs_passes"
    else
        echo "FAIL test_no_refs_passes: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 2: commit with all-existing paths → exit 0
# -----------------------------------------------------------------------------
test_all_existing_passes() {
    local f; f=$(mktemp -d); scaffold "$f"
    mkdir -p "$f/docs/scripts" "$f/scripts" "$f/tests"
    : > "$f/scripts/foo.sh"
    : > "$f/docs/scripts/foo.sh.md"
    : > "$f/tests/test_foo.sh"
    git -C "$f" add . >/dev/null
    git -C "$f" commit -q -m "tooling: add foo gate

Closes the gate per §X.Y. New files:
- scripts/foo.sh
- docs/scripts/foo.sh.md
- tests/test_foo.sh"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_all_existing_passes"
    else
        echo "FAIL test_all_existing_passes: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 3: orphan reference (file does not exist) → exit 1
# -----------------------------------------------------------------------------
test_orphan_ref_rejected() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_body "$f" "claim: see evidence at .lava-ci-evidence/sixth-law-incidents/2026-99-99-phantom.json for full details"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "2026-99-99-phantom.json"; then
        echo "PASS test_orphan_ref_rejected"
    else
        echo "FAIL test_orphan_ref_rejected: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 4: stale ref inside ~~strikethrough~~ → exit 0 (skipped)
# -----------------------------------------------------------------------------
test_strikethrough_skipped() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_body "$f" "closure: archived ~~docs/old-removed-design.md~~ as obsolete"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_strikethrough_skipped"
    else
        echo "FAIL test_strikethrough_skipped: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 5: stale ref inside backticks → exit 0 (skipped)
# -----------------------------------------------------------------------------
test_backtick_skipped() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_body "$f" "docs: clarified that \`scripts/never-existed.sh\` is just an example pattern"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_backtick_skipped"
    else
        echo "FAIL test_backtick_skipped: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 6: fuzzy basename fallback resolves abbreviated prose paths → exit 0
# -----------------------------------------------------------------------------
test_fuzzy_basename_passes() {
    local f; f=$(mktemp -d); scaffold "$f"
    mkdir -p "$f/feature/search_result/src/main/kotlin/lava/search/result"
    : > "$f/feature/search_result/src/main/kotlin/lava/search/result/SearchPageState.kt"
    git -C "$f" add . >/dev/null
    git -C "$f" commit -q -m "fix: refactored variant in feature/search_result/SearchPageState.kt
(short-form prose reference; real path is feature/search_result/src/main/kotlin/...)"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_fuzzy_basename_passes"
    else
        echo "FAIL test_fuzzy_basename_passes: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 7: 4-space-indent skip — Bluff-Audit/quoted-output content
# -----------------------------------------------------------------------------
test_indented_quoted_output_skipped() {
    local f; f=$(mktemp -d); scaffold "$f"
    mkdir -p "$f/scripts"
    : > "$f/scripts/foo.sh"   # the Bluff-Audit header cites this real file
    git -C "$f" add . >/dev/null
    git -C "$f" commit -q -m "fix: closure-log added

Bluff-Audit: scripts/foo.sh
  Mutation: deleted scripts/foo-not-real.sh
  Observed-Failure: \"VIOLATION: commit XXX: scripts/foo-not-real.sh
    → directive to remediate\"
  Reverted: yes"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_indented_quoted_output_skipped"
    else
        echo "FAIL test_indented_quoted_output_skipped: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 8: real-repo sanity check at HEAD
# -----------------------------------------------------------------------------
test_real_repo_passes() {
    local out rc
    out=$(bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "gate clean"; then
        echo "PASS test_real_repo_passes"
    else
        echo "FAIL test_real_repo_passes: rc=$rc out=$out"; exit 1
    fi
}

test_no_refs_passes
test_all_existing_passes
test_orphan_ref_rejected
test_strikethrough_skipped
test_backtick_skipped
test_fuzzy_basename_passes
test_indented_quoted_output_skipped
test_real_repo_passes

echo "All 8 commit-docs-exists gate tests PASSED"
