#!/usr/bin/env bash
# Tests for the §6.H credential-scan corpus assertion in
# scripts/check-constitution.sh (added 2026-08-22 by the §6.N.2 gate-shaping
# bluff hunt).
#
# The defect this guards: the scan corpus comes from
#   mapfile -t tracked_files < <( git ls-files 2>/dev/null | grep -vE '<exempt>' || true )
# When `git ls-files` yields nothing the array is empty, the scan loop runs
# zero times, credential_violations stays 0, and the script prints
# "no clause-6.H credential patterns in tracked files" and exits 0 — a PASS
# that examined nothing. That is §6.J's "nothing was learned reported as
# nothing failed".
#
# Falsifiability rehearsal per §6.J clause 2: test 1 removes the corpus and
# asserts the gate now REFUSES; test 3 re-introduces a real credential
# pattern into a non-empty corpus and asserts the gate still catches it, so
# the new guard cannot be mistaken for the detection it protects.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-constitution.sh"

if [[ ! -f "$SCANNER" ]]; then
    echo "FAIL: scanner not found at $SCANNER"
    exit 1
fi

# Build a PATH shim whose `git ls-files` emits exactly the lines given in
# $LAVA_TEST_LSFILES (one per line, empty = no output). Every other git
# subcommand is delegated to the real binary, so the rest of
# check-constitution.sh runs against the real tree unchanged.
_make_git_stub() {
    local dir=$1 real
    real=$(command -v git)
    mkdir -p "$dir"
    cat > "$dir/git" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "ls-files" ]]; then
    [[ -n "\${LAVA_TEST_LSFILES:-}" ]] && printf '%s\n' "\$LAVA_TEST_LSFILES"
    exit 0
fi
exec "$real" "\$@"
EOF
    chmod +x "$dir/git"
}

# -----------------------------------------------------------------------------
# Test 1: empty corpus → gate REFUSES (exit 1) instead of claiming a clean scan
# -----------------------------------------------------------------------------
test_empty_corpus_refused() {
    local d out rc
    d=$(mktemp -d); _make_git_stub "$d/bin"
    out=$(PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -ne 0 ]] && grep -q 'examined ZERO tracked files' <<<"$out"; then
        echo "PASS test_empty_corpus_refused"
    else
        echo "FAIL test_empty_corpus_refused: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 2: non-empty clean corpus → guard does NOT fire; script runs to completion
# -----------------------------------------------------------------------------
test_clean_corpus_passes() {
    local d out rc
    d=$(mktemp -d); _make_git_stub "$d/bin"
    out=$(PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="README.md" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && grep -q 'Constitution check passed' <<<"$out" \
       && ! grep -q 'examined ZERO tracked files' <<<"$out"; then
        echo "PASS test_clean_corpus_passes"
    else
        echo "FAIL test_clean_corpus_passes: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 3: the guard did NOT displace real detection — a genuine §6.H pattern in
# a non-empty corpus is still caught.
# -----------------------------------------------------------------------------
test_real_violation_still_caught() {
    local d out rc leak
    d=$(mktemp -d); _make_git_stub "$d/bin"
    # The scan loop resolves paths relative to the repo root, so the fixture
    # must live inside it. Use a uniquely-named temp file and remove it after.
    leak="lava-bluffhunt-fixture-$$.kt"
    printf 'private object CredsBridge {\n}\n' > "$REPO_ROOT/$leak"
    out=$(cd "$REPO_ROOT" && PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="$leak" bash "$SCANNER" 2>&1)
    rc=$?
    rm -f "$REPO_ROOT/$leak"
    if [[ "$rc" -ne 0 ]] && grep -q 'clause 6.H violation' <<<"$out"; then
        echo "PASS test_real_violation_still_caught"
    else
        echo "FAIL test_real_violation_still_caught: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

test_empty_corpus_refused
test_clean_corpus_passes
test_real_violation_still_caught

echo "All 3 credential-scan corpus tests PASSED"
