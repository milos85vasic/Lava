#!/usr/bin/env bash
# Tests for .githooks/pre-push Check 5 (§6.N.1.3 enforcement).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/.githooks/pre-push"

run_hook() {
  local fixture_dir=$1 sha=$2
  cd "$fixture_dir"
  echo "refs/heads/master $sha refs/heads/master 0000000000000000000000000000000000000000" | \
    "$HOOK" origin "$fixture_dir" 2>&1
}

test_attestation_no_rehearsal_rejected() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p .lava-ci-evidence/sixth-law-incidents
  echo '{"unrelated": "data"}' > .lava-ci-evidence/sixth-law-incidents/2026-05-99-fake.json
  git add . && git commit -qm "add attestation no rehearsal"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.3 violation"; then
    echo "PASS test_attestation_no_rehearsal_rejected"
  else
    echo "FAIL test_attestation_no_rehearsal_rejected: got: $output"
    exit 1
  fi
}

test_attestation_with_embedded_rehearsal_accepted() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p .lava-ci-evidence/sixth-law-incidents
  echo '{"falsifiability_rehearsal": {"mutation": "x", "observed_failure": "y", "reverted": true}}' \
    > .lava-ci-evidence/sixth-law-incidents/2026-05-99-fake.json
  git add . && git commit -qm "add attestation with embedded rehearsal"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.3 violation"; then
    echo "FAIL test_attestation_with_embedded_rehearsal_accepted: should have accepted, got: $output"
    exit 1
  else
    echo "PASS test_attestation_with_embedded_rehearsal_accepted"
  fi
}

test_attestation_with_companion_file_accepted() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p .lava-ci-evidence/sp3a-challenges
  echo '{"any": "content"}' > .lava-ci-evidence/sp3a-challenges/C99-test.json
  echo '{"mutation": "x"}' > .lava-ci-evidence/sp3a-challenges/C99-test.rehearsal.json
  git add . && git commit -qm "add attestation with companion"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.3 violation"; then
    echo "FAIL test_attestation_with_companion_file_accepted: got: $output"
    exit 1
  else
    echo "PASS test_attestation_with_companion_file_accepted"
  fi
}

test_attestation_with_commit_bluffaudit_accepted() {
  local f
  f=$(mktemp -d)
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p .lava-ci-evidence/sixth-law-incidents
  echo '{"any": "content"}' > .lava-ci-evidence/sixth-law-incidents/2026-05-99-fake.json
  git add .
  git commit -qm "add attestation with commit-body Bluff-Audit

Bluff-Audit: .lava-ci-evidence/sixth-law-incidents/2026-05-99-fake.json
  Mutation: synthetic
  Reverted: yes"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.N.1.3 violation"; then
    echo "FAIL test_attestation_with_commit_bluffaudit_accepted: got: $output"
    exit 1
  else
    echo "PASS test_attestation_with_commit_bluffaudit_accepted"
  fi
}

test_attestation_no_rehearsal_rejected
test_attestation_with_embedded_rehearsal_accepted
test_attestation_with_companion_file_accepted
test_attestation_with_commit_bluffaudit_accepted
echo "all check5 tests passed"
