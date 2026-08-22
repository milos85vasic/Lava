#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-00-precondition.sh (FR-000).
#
# This test constructs isolated, throwaway git fixture repositories under a
# temp directory and invokes the real production script against each one via
# the script's optional first-argument repo-path override. It never touches
# this actual repository's git state.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

SCRIPT_UNDER_TEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/pipeline/phase-00-precondition.sh"

# All fixture directories created during this run, cleaned up on exit no
# matter how the script terminates (success, failure, or interrupt).
FIXTURE_DIRS=()

cleanup() {
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
      rm -rf -- "$d"
    fi
  done
}
trap cleanup EXIT

# make_fixture_repo <name> — creates a fresh temp git repo with an initial
# commit on a `master` branch. Echoes the fixture path on stdout.
#
# NOTE: this function is invoked via command substitution (`dir="$(...)"`) by
# every caller below, and command substitution runs in a subshell in bash —
# so any mutation this function makes to FIXTURE_DIRS here would NOT be
# visible in the parent shell, and the EXIT trap would silently have nothing
# to clean up. To avoid that trap, this function does NOT register the
# fixture itself; every call site registers the returned path in
# FIXTURE_DIRS immediately after capturing it. See callers below.
make_fixture_repo() {
  local name="$1"
  local dir
  dir="$(mktemp -d "${TMPDIR:-/tmp}/phase00-fixture-${name}-XXXXXX")"

  git -C "$dir" init --quiet --initial-branch=master
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"
  echo "seed" > "$dir/seed.txt"
  git -C "$dir" add seed.txt
  git -C "$dir" commit --quiet -m "initial commit"

  echo "$dir"
}

FAILURES=0

# --- Case 1: not on master -------------------------------------------------
case1_dir="$(make_fixture_repo "wrong-branch")"
FIXTURE_DIRS+=("$case1_dir")
git -C "$case1_dir" checkout --quiet -b feature/not-master

case1_output="$("$SCRIPT_UNDER_TEST" "$case1_dir" 2>&1)" && case1_exit=0 || case1_exit=$?
if [[ "$case1_exit" -eq 2 ]]; then
  if echo "$case1_output" | grep -qi "branch"; then
    echo "PASS: not-on-master exits 2 and names the branch precondition"
  else
    echo "FAIL: not-on-master exited 2 but message did not name the branch precondition: $case1_output"
    FAILURES=$((FAILURES + 1))
  fi
else
  echo "FAIL: not-on-master expected exit 2, got $case1_exit; output: $case1_output"
  FAILURES=$((FAILURES + 1))
fi

# --- Case 2: on master but dirty tree --------------------------------------
case2_dir="$(make_fixture_repo "dirty-tree")"
FIXTURE_DIRS+=("$case2_dir")
echo "uncommitted change" >> "$case2_dir/seed.txt"

case2_output="$("$SCRIPT_UNDER_TEST" "$case2_dir" 2>&1)" && case2_exit=0 || case2_exit=$?
if [[ "$case2_exit" -eq 2 ]]; then
  if echo "$case2_output" | grep -qi "clean"; then
    echo "PASS: dirty-tree-on-master exits 2 and names the clean-tree precondition"
  else
    echo "FAIL: dirty-tree-on-master exited 2 but message did not name the clean-tree precondition: $case2_output"
    FAILURES=$((FAILURES + 1))
  fi
else
  echo "FAIL: dirty-tree-on-master expected exit 2, got $case2_exit; output: $case2_output"
  FAILURES=$((FAILURES + 1))
fi

# --- Case 3: on master, clean tree -----------------------------------------
case3_dir="$(make_fixture_repo "clean-master")"
FIXTURE_DIRS+=("$case3_dir")

case3_output="$("$SCRIPT_UNDER_TEST" "$case3_dir" 2>&1)" && case3_exit=0 || case3_exit=$?
if [[ "$case3_exit" -eq 0 ]]; then
  echo "PASS: clean-master exits 0"
else
  echo "FAIL: clean-master expected exit 0, got $case3_exit; output: $case3_output"
  FAILURES=$((FAILURES + 1))
fi

echo "---"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: all phase-00-precondition tests passed"
  exit 0
else
  echo "FAIL: $FAILURES phase-00-precondition test case(s) failed"
  exit 1
fi
