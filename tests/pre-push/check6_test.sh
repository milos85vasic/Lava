#!/usr/bin/env bash
# Tests for .githooks/pre-push Check 6 (§6.Y bump-first ordering).
# Builds synthetic git fixtures with code-touching commits at various
# version-code states, runs the hook, asserts on the result.
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
  echo "1042" > .lava-ci-evidence/distribute-changelog/firebase-app-distribution/last-version-debug
  git add . && git commit -qm "init at vc=1042"
}

# Test 1: code commit at vc=1042 with last-published-debug=1042 (no bump)
#         → should fire §6.Y violation
test_no_bump_after_distribute_rejected() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  echo "// new feature change" > app/Foo.kt
  git add app/Foo.kt
  git commit -qm "feature: new functionality"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.Y violation"; then
    echo "PASS test_no_bump_after_distribute_rejected"
  else
    echo "FAIL test_no_bump_after_distribute_rejected: expected §6.Y violation, got: $output"
    exit 1
  fi
}

# Test 2: code commit at vc=1043 (bumped) → no violation
test_bump_first_accepted() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  cat > app/build.gradle.kts <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1043
        versionName = "1.2.23"
    }
}
GRADLE
  echo "// new feature change" > app/Foo.kt
  git add . && git commit -qm "feature: bumped + change"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.Y violation"; then
    echo "FAIL test_bump_first_accepted: should accept (versionCode bumped to 1043), got: $output"
    exit 1
  else
    echo "PASS test_bump_first_accepted"
  fi
}

# Test 3: code commit without bump but with constitutional-plumbing-only marker → accepted
test_constitutional_plumbing_only_opt_out_accepted() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  echo "// docs change" > app/README.kt
  git add app/README.kt
  git commit -qm "docs: minor

constitutional-plumbing-only."
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.Y violation"; then
    echo "FAIL test_constitutional_plumbing_only_opt_out_accepted: should accept (opt-out marker), got: $output"
    exit 1
  else
    echo "PASS test_constitutional_plumbing_only_opt_out_accepted"
  fi
}

# Test 4: doc-only commit (no code-surface files) → Check 6 does NOT trigger
test_doc_only_skipped() {
  local f
  f=$(mktemp -d)
  build_fixture "$f"
  mkdir -p docs
  echo "doc" > docs/README.md
  git add docs/README.md && git commit -qm "docs: only"
  local sha
  sha=$(git rev-parse HEAD)
  output=$(run_hook "$f" "$sha" || true)
  if echo "$output" | grep -q "§6.Y violation"; then
    echo "FAIL test_doc_only_skipped: Check 6 should not fire on docs-only, got: $output"
    exit 1
  else
    echo "PASS test_doc_only_skipped"
  fi
}

test_no_bump_after_distribute_rejected
test_bump_first_accepted
test_constitutional_plumbing_only_opt_out_accepted
test_doc_only_skipped
echo "all check6 tests passed"
