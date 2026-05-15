#!/usr/bin/env bash
# Tests for .githooks/pre-push Check 9 (§11.4.18 / CM-SCRIPT-DOCS-SYNC
# script doc sync).
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

build_fixture_with_doc() {
  local f=$1
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  mkdir -p scripts docs/scripts
  echo "echo hi" > scripts/foo.sh
  echo "# foo guide" > docs/scripts/foo.sh.md
  git add . && git commit -qm "init script + companion doc"
}

build_fixture_no_doc() {
  local f=$1
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  mkdir -p scripts
  echo "echo hi" > scripts/foo.sh
  git add . && git commit -qm "init script (no doc)"
}

# Test 1: modify script WITHOUT updating companion doc → violation
test_script_change_without_doc_update_rejected() {
  local f
  f=$(mktemp -d)
  build_fixture_with_doc "$f"
  echo "echo y" >> scripts/foo.sh
  git add scripts/foo.sh
  git commit -qm "fix: foo behavior"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "CM-SCRIPT-DOCS-SYNC violation"; then
    echo "PASS test_script_change_without_doc_update_rejected"
  else
    echo "FAIL test_script_change_without_doc_update_rejected: expected CM-SCRIPT-DOCS-SYNC violation, got: $output"
    exit 1
  fi
}

# Test 2: modify script AND companion doc in same commit → accepted
test_script_change_with_doc_update_accepted() {
  local f
  f=$(mktemp -d)
  build_fixture_with_doc "$f"
  echo "echo y" >> scripts/foo.sh
  echo "## new section" >> docs/scripts/foo.sh.md
  git add . && git commit -qm "fix: foo + doc update"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "CM-SCRIPT-DOCS-SYNC violation"; then
    echo "FAIL test_script_change_with_doc_update_accepted: should accept, got: $output"
    exit 1
  else
    echo "PASS test_script_change_with_doc_update_accepted"
  fi
}

# Test 3: modify script with NO companion doc existing yet → Check 9 does NOT fire
test_script_without_existing_doc_skipped() {
  local f
  f=$(mktemp -d)
  build_fixture_no_doc "$f"
  echo "echo y" >> scripts/foo.sh
  git add scripts/foo.sh
  git commit -qm "fix: foo (no companion exists yet)"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "CM-SCRIPT-DOCS-SYNC violation"; then
    echo "FAIL test_script_without_existing_doc_skipped: Check 9 should not fire when no doc exists, got: $output"
    exit 1
  else
    echo "PASS test_script_without_existing_doc_skipped"
  fi
}

test_script_change_without_doc_update_rejected
test_script_change_with_doc_update_accepted
test_script_without_existing_doc_skipped
echo "all check9 tests passed"
