#!/usr/bin/env bash
# Tests for .githooks/pre-push Check 4 (§6.N.1.2 enforcement).
# Builds a synthetic git fixture, makes commits with various
# Bluff-Audit-stamp shapes, runs the hook, asserts on the result.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/.githooks/pre-push"

# Helper: run the hook against a fixture, capture stderr + exit code.
run_hook() {
  local fixture_dir=$1 sha=$2
  cd "$fixture_dir"
  # The pre-push hook reads stdin in the format "local-ref local-sha
  # remote-ref remote-sha". Synthesize a single line.
  echo "refs/heads/master $sha refs/heads/master 0000000000000000000000000000000000000000" | \
    "$HOOK" origin "$fixture_dir" 2>&1
  echo "exit=$?"
}

# Test 1: gate-shaping file change without any Bluff-Audit stamp → violation
test_no_stamp_rejected() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  # Initial commit required so the test commit is not a root commit
  # (git diff-tree needs a parent to produce file list).
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p scripts
  echo "echo hi" > scripts/check-constitution.sh
  git add . && git commit -qm "touch gate file no stamp"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.2 violation"; then
    echo "PASS test_no_stamp_rejected"
  else
    echo "FAIL test_no_stamp_rejected: expected §6.N.1.2 violation, got: $output"
    exit 1
  fi
}

# Test 2: gate-shaping file change with Bluff-Audit stamp naming a DIFFERENT file → violation
test_stamp_unrelated_file_rejected() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p scripts
  echo "echo hi" > scripts/check-constitution.sh
  git add .
  git commit -qm "touch gate file with unrelated stamp

Bluff-Audit: some-other-file.go
  Mutation: irrelevant
  Reverted: yes"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.2 violation.*does NOT name any file in the diff"; then
    echo "PASS test_stamp_unrelated_file_rejected"
  else
    echo "FAIL test_stamp_unrelated_file_rejected: expected unrelated-file violation, got: $output"
    exit 1
  fi
}

# Test 3: gate-shaping file change with Bluff-Audit stamp naming the touched file → accepted
test_stamp_matches_diff_accepted() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p scripts
  echo "echo hi" > scripts/check-constitution.sh
  git add .
  git commit -qm "touch gate file with matching stamp

Bluff-Audit: scripts/check-constitution.sh
  Mutation: comment out the §6.N check
  Reverted: yes"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.2 violation"; then
    echo "FAIL test_stamp_matches_diff_accepted: should have accepted, got: $output"
    exit 1
  else
    echo "PASS test_stamp_matches_diff_accepted"
  fi
}

# Test 4: non-gate-shaping file change → Check 4 does NOT trigger
test_non_gate_file_skipped() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  echo "hi" > README.md
  git add . && git commit -qm "non-gate change"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.2 violation"; then
    echo "FAIL test_non_gate_file_skipped: Check 4 should not fire on README.md, got: $output"
    exit 1
  else
    echo "PASS test_non_gate_file_skipped"
  fi
}

test_no_stamp_rejected
test_stamp_unrelated_file_rejected
test_stamp_matches_diff_accepted
test_non_gate_file_skipped
echo "all check4 tests passed"
