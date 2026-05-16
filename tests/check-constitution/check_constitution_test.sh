#!/usr/bin/env bash
# Tests for scripts/check-constitution.sh §6.N awareness (Group A-prime).
# The script is sensitive to file structure (CLAUDE.md headings,
# submodules/* paths). Easiest way to test: run it against the live
# repo + against a temporary fixture that DELETES specific structures.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/check-constitution.sh"

# Cross-platform sed -i: BSD sed (macOS) requires an explicit backup
# extension argument; GNU sed (Linux) accepts the same form. Using
# `sed -i.bak` and deleting the .bak afterwards works on both. Without
# this helper, the pre-fix invocations of `sed -i 's|...|...|' FILE`
# treated `s|...|...|` as the backup extension and `FILE` as the
# script on darwin, exiting with `sed: 1: "FILE": invalid command code`
# and silently aborting the test before any assertion ran.
sed_inplace() {
  local expr="$1"
  local file="$2"
  sed -i.sedbak "$expr" "$file"
  rm -f "$file.sedbak"
}

# Test 1: live repo passes (assumes Group A is in place).
test_live_repo_passes() {
  cd "$REPO_ROOT"
  if "$SCRIPT" >/dev/null 2>&1; then
    echo "PASS test_live_repo_passes"
  else
    echo "FAIL test_live_repo_passes: script failed against live repo"
    "$SCRIPT" || true
    exit 1
  fi
}

# Helper: copy repo to fixture, install local copy of the script so that
# check-constitution.sh's `cd "$(dirname "$0")/.."` resolves to the fixture
# root rather than the live repo root.
make_fixture() {
  local fixture
  fixture=$(mktemp -d)
  cp -r "$REPO_ROOT/." "$fixture/" 2>/dev/null || true
  mkdir -p "$fixture/scripts"
  cp "$SCRIPT" "$fixture/scripts/check-constitution.sh"
  echo "$fixture"
}

# Test 2: stripped CLAUDE.md (no §6.N heading) → script hard-fails.
test_missing_6n_heading_fails() {
  local fixture
  fixture=$(make_fixture)
  cd "$fixture"
  # Remove the §6.N heading (replace with placeholder).
  sed_inplace 's|^##### 6\.N — Bluff-Hunt Cadence|##### 6.X — placeholder|' CLAUDE.md
  if "$fixture/scripts/check-constitution.sh" >/dev/null 2>&1; then
    echo "FAIL test_missing_6n_heading_fails: script passed despite missing §6.N"
    exit 1
  else
    echo "PASS test_missing_6n_heading_fails"
  fi
  rm -rf "$fixture"
}

# Test 3: missing §6.N from a submodule CLAUDE.md → script hard-fails.
test_missing_6n_from_submodule_fails() {
  local fixture
  fixture=$(make_fixture)
  cd "$fixture"
  if [[ -f submodules/auth/CLAUDE.md ]]; then
    # Strip all 6.N references from Auth's CLAUDE.md
    sed_inplace '/6\.N/d' submodules/auth/CLAUDE.md
    if "$fixture/scripts/check-constitution.sh" >/dev/null 2>&1; then
      echo "FAIL test_missing_6n_from_submodule_fails: script passed despite Auth missing §6.N"
      exit 1
    else
      echo "PASS test_missing_6n_from_submodule_fails"
    fi
  else
    echo "SKIP test_missing_6n_from_submodule_fails: submodules/auth/CLAUDE.md not present"
  fi
  rm -rf "$fixture"
}

# Test 4: missing pre-push Check 4 marker → hard-fails.
test_missing_check4_marker_fails() {
  local fixture
  fixture=$(make_fixture)
  cd "$fixture"
  sed_inplace '/# ===== Check 4: §6.N.1.2/d' .githooks/pre-push
  if "$fixture/scripts/check-constitution.sh" >/dev/null 2>&1; then
    echo "FAIL test_missing_check4_marker_fails: script passed despite missing Check 4 marker"
    exit 1
  else
    echo "PASS test_missing_check4_marker_fails"
  fi
  rm -rf "$fixture"
}


# Test 5: missing pre-push Check 5 marker → hard-fails.
test_missing_check5_marker_fails() {
  local fixture
  fixture=$(make_fixture)
  cd "$fixture"
  sed_inplace '/# ===== Check 5: §6.N.1.3/d' .githooks/pre-push
  if "$fixture/scripts/check-constitution.sh" >/dev/null 2>&1; then
    echo "FAIL test_missing_check5_marker_fails: script passed despite missing Check 5 marker"
    exit 1
  else
    echo "PASS test_missing_check5_marker_fails"
  fi
  rm -rf "$fixture"
}

test_live_repo_passes
test_missing_6n_heading_fails
test_missing_6n_from_submodule_fails
test_missing_check4_marker_fails
test_missing_check5_marker_fails
echo "all check-constitution tests passed"
