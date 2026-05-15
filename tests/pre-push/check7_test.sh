#!/usr/bin/env bash
# Tests for .githooks/pre-push Check 7 (§6.Z evidence-file presence on
# pointer advance).
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
  mkdir -p app .lava-ci-evidence/distribute-changelog/firebase-app-distribution
  cat > app/build.gradle.kts <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1042
        versionName = "1.2.22"
    }
}
GRADLE
  # Seed the per-version snapshot for the new pointer value
  echo "## Lava-Android-1.2.22-1042 — initial snapshot" > \
    .lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.2.22-1042.md
  echo "1041" > .lava-ci-evidence/distribute-changelog/firebase-app-distribution/last-version-debug
  git add . && git commit -qm "init at vc=1042 / pointer=1041"
}

# Test 1: pointer advance 1041→1042 WITHOUT evidence file → §6.Z violation
test_pointer_advance_without_evidence_rejected() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  echo "1042" > .lava-ci-evidence/distribute-changelog/firebase-app-distribution/last-version-debug
  git add . && git commit -qm "advance pointer 1041→1042"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.Z violation"; then
    echo "PASS test_pointer_advance_without_evidence_rejected"
  else
    echo "FAIL test_pointer_advance_without_evidence_rejected: expected §6.Z violation, got: $output"
    exit 1
  fi
}

# Test 2: pointer advance WITH evidence file → accepted
test_pointer_advance_with_evidence_accepted() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  echo "1042" > .lava-ci-evidence/distribute-changelog/firebase-app-distribution/last-version-debug
  echo "## evidence" > .lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.2.22-1042-test-evidence.md
  git add . && git commit -qm "advance pointer 1041→1042 with evidence"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.Z violation"; then
    echo "FAIL test_pointer_advance_with_evidence_accepted: should accept, got: $output"
    exit 1
  else
    echo "PASS test_pointer_advance_with_evidence_accepted"
  fi
}

# Test 3: pointer touched but VALUE unchanged → no violation (no real advance)
test_pointer_touch_without_value_change_skipped() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  # Touch the file but write the same value
  echo "1041" > .lava-ci-evidence/distribute-changelog/firebase-app-distribution/last-version-debug
  # Force a tracked diff via a sibling change
  touch .lava-ci-evidence/distribute-changelog/firebase-app-distribution/.placeholder
  git add . && git commit -qm "touch pointer (no value change)"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  # Either no §6.Z line OR §6.Z absent — both acceptable
  if echo "$output" | grep -q "§6.Z violation"; then
    echo "FAIL test_pointer_touch_without_value_change_skipped: false positive on touch-without-advance, got: $output"
    exit 1
  else
    echo "PASS test_pointer_touch_without_value_change_skipped"
  fi
}

test_pointer_advance_without_evidence_rejected
test_pointer_advance_with_evidence_accepted
test_pointer_touch_without_value_change_skipped
echo "all check7 tests passed"
