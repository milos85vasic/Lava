#!/usr/bin/env bash
# phase-00-precondition.sh — Pipeline safety boundary (FR-000).
#
# Refuses to let the build-test-distribute pipeline proceed unless the
# working tree is on the `master` branch with a completely clean `git
# status`. Later pipeline phases auto-distribute app releases and auto-push
# to git remotes with zero human pause, so this guard is the pipeline's only
# safety boundary against accidentally doing that from an unreviewed feature
# branch or a dirty tree.
#
# Usage:
#   scripts/pipeline/phase-00-precondition.sh [repo-path]
#
# With no argument, checks the repository containing the current working
# directory (via `git rev-parse --show-toplevel`), so it works when invoked
# from anywhere inside the real repo. An optional first argument overrides
# which repo to check — this is how the hermetic test suite in
# tests/pipeline/test_phase_00_precondition.sh points it at a disposable git
# fixture directory without touching this repository's actual git state.
# Nothing here is hardcoded to a particular repo path or branch fixture; the
# repo path is always resolved at runtime, either from argv or from `git`
# itself.
#
# Exit codes:
#   0 - on master, working tree clean; pipeline may proceed.
#   2 - precondition failed (wrong branch, or dirty tree).
# No other exit codes are defined by this script.

set -euo pipefail

REPO_PATH="${1:-}"

if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

current_branch="$(git -C "$REPO_PATH" branch --show-current)"

if [[ "$current_branch" != "master" ]]; then
  echo "FR-000: precondition failed — branch is not master (currently on '${current_branch}')" >&2
  exit 2
fi

dirty_status="$(git -C "$REPO_PATH" status --porcelain)"

if [[ -n "$dirty_status" ]]; then
  echo "FR-000: precondition failed — working tree is not clean" >&2
  exit 2
fi

echo "FR-000: precondition satisfied — on master, working tree clean"
exit 0
