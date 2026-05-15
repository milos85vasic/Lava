#!/usr/bin/env bash
# Tests for scripts/check-gitignore-coverage.sh — §11.4.30 enforcement.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-gitignore-coverage.sh"

run_scanner() {
    local fixture=$1 mode=${2:-strict}
    LAVA_REPO_ROOT="$fixture" \
        LAVA_GITIGNORE_STRICT="$([[ "$mode" == "strict" ]] && echo 1 || echo 0)" \
        bash "$SCANNER" 2>&1
    echo "exit=$?"
}

# Test 1: real Lava tree (current state) → all modules covered + no forbidden tracked
test_real_tree_passes() {
    cd "$REPO_ROOT"
    LAVA_REPO_ROOT="$REPO_ROOT" bash "$SCANNER" > /dev/null 2>&1
    local rc=$?
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_real_tree_passes: expected exit 0 on real Lava tree, got $rc"
        exit 1
    fi
    echo "PASS test_real_tree_passes"
}

# Test 2: synthetic fixture with module missing .gitignore → reject
test_module_without_gitignore_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p app feature/foo core
    touch app/.gitignore
    touch feature/foo/build.gradle.kts  # module
    # NO feature/foo/.gitignore
    local out
    out=$(run_scanner "$f" strict)
    if echo "$out" | grep -qE "Missing .gitignore.*1|feature/foo"; then
        echo "PASS test_module_without_gitignore_rejected"
    else
        echo "FAIL test_module_without_gitignore_rejected: expected feature/foo flagged, got: $out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 3: synthetic fixture with all modules .gitignored → pass
test_clean_fixture_passes() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p app
    touch app/.gitignore
    local out
    out=$(run_scanner "$f" strict)
    if echo "$out" | grep -qE "all modules have .gitignore"; then
        echo "PASS test_clean_fixture_passes"
    else
        echo "FAIL test_clean_fixture_passes: expected clean pass, got: $out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

test_real_tree_passes
test_module_without_gitignore_rejected
test_clean_fixture_passes
echo "all gitignore-coverage tests passed"
