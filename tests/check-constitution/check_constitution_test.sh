#!/usr/bin/env bash
# Tests for scripts/check-constitution.sh §6.N awareness (Group A-prime).
# The script is sensitive to file structure (CLAUDE.md headings,
# Submodules/* paths). Easiest way to test: run it against the live
# repo + against a temporary fixture that DELETES specific structures.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/check-constitution.sh"

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
  sed -i 's|^##### 6\.N — Bluff-Hunt Cadence|##### 6.X — placeholder|' CLAUDE.md
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
  if [[ -f Submodules/Auth/CLAUDE.md ]]; then
    # Strip all 6.N references from Auth's CLAUDE.md
    sed -i '/6\.N/d' Submodules/Auth/CLAUDE.md
    if "$fixture/scripts/check-constitution.sh" >/dev/null 2>&1; then
      echo "FAIL test_missing_6n_from_submodule_fails: script passed despite Auth missing §6.N"
      exit 1
    else
      echo "PASS test_missing_6n_from_submodule_fails"
    fi
  else
    echo "SKIP test_missing_6n_from_submodule_fails: Submodules/Auth/CLAUDE.md not present"
  fi
  rm -rf "$fixture"
}

# Test 4: missing pre-push Check 4 marker → hard-fails.
test_missing_check4_marker_fails() {
  local fixture
  fixture=$(make_fixture)
  cd "$fixture"
  sed -i '/# ===== Check 4: §6.N.1.2/d' .githooks/pre-push
  if "$fixture/scripts/check-constitution.sh" >/dev/null 2>&1; then
    echo "FAIL test_missing_check4_marker_fails: script passed despite missing Check 4 marker"
    exit 1
  else
    echo "PASS test_missing_check4_marker_fails"
  fi
  rm -rf "$fixture"
}

test_live_repo_passes
test_missing_6n_heading_fails
test_missing_6n_from_submodule_fails
test_missing_check4_marker_fails
echo "all check-constitution tests passed"
