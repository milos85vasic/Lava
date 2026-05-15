#!/usr/bin/env bash
# Tests for .githooks/pre-push Check 8 (§11.4.17 Classification: line
# on new-rule commits).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/.githooks/pre-push"

run_hook() {
  local fixture_dir=$1 sha=$2
  cd "$fixture_dir"
  echo "refs/heads/master $sha refs/heads/master 0000000000000000000000000000000000000000" | \
    "$HOOK" origin "$fixture_dir" 2>&1
  echo "exit=$?"
}

build_fixture() {
  local f=$1
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "# CLAUDE.md" > CLAUDE.md
  echo "init" >> CLAUDE.md
  git add . && git commit -qm "init CLAUDE.md"
}

# Test 1: new ##### 6.X clause without Classification: line → violation
test_new_clause_without_classification_rejected() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  cat >> CLAUDE.md <<'C'

##### 6.AE — Test New Clause

This is a new constitutional clause for testing.
C
  git add CLAUDE.md
  git commit -qm "constitution: new 6.AE clause without classification"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§11.4.17 violation"; then
    echo "PASS test_new_clause_without_classification_rejected"
  else
    echo "FAIL test_new_clause_without_classification_rejected: expected §11.4.17 violation, got: $output"
    exit 1
  fi
}

# Test 2: new ##### 6.X clause WITH Classification: line → accepted
test_new_clause_with_classification_accepted() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  cat >> CLAUDE.md <<'C'

##### 6.AE — Test New Clause

This is a new constitutional clause for testing.
C
  git add CLAUDE.md
  git commit -qm "constitution: new 6.AE clause

Classification: project-specific (test fixture only)."
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§11.4.17 violation"; then
    echo "FAIL test_new_clause_with_classification_accepted: should accept, got: $output"
    exit 1
  else
    echo "PASS test_new_clause_with_classification_accepted"
  fi
}

# Test 3: docs-only commit without ##### 6.X clause → Check 8 does NOT trigger
test_doc_only_skipped() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  echo "minor edit" >> CLAUDE.md
  git add CLAUDE.md
  git commit -qm "docs: minor edit"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§11.4.17 violation"; then
    echo "FAIL test_doc_only_skipped: Check 8 fired on non-clause edit, got: $output"
    exit 1
  else
    echo "PASS test_doc_only_skipped"
  fi
}

test_new_clause_without_classification_rejected
test_new_clause_with_classification_accepted
test_doc_only_skipped
echo "all check8 tests passed"
