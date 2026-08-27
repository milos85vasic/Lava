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

# §6.J corpus floor (added 2026-08-26, LVA vacuous-pass sweep P11 — recorded
# there as UNCONFIRMED, CONFIRMED by fixture on 2026-08-26).
#
# `--ignore-submodules=none` is REQUIRED here. .gitmodules sets
# `ignore = untracked` for submodules/containers (:4) and submodules/challenges
# (:32), and a bare `git status --porcelain` honours that setting — so untracked
# files inside either submodule are invisible to this guard and the tree reads
# clean when it is not:
#
#   REPRO (parent with submodule.<name>.ignore=untracked, one untracked file
#          planted inside that submodule):
#     git status --porcelain                     -> (empty)
#     FR-000: precondition satisfied — ... clean   EXIT=0
#   CONTROL (same tree, same moment):
#     git status --porcelain --ignore-submodules=none ->  M submodules/containers
#
# FR-018/SC-007 require that a run neither starts from nor leaves a dirty tree;
# a guard that cannot see two of the submodules cannot make that claim. The
# corpus here is the working tree itself, and `ignore = untracked` silently
# removes part of it.
dirty_status="$(git -C "$REPO_PATH" status --porcelain --ignore-submodules=none)"

if [[ -n "$dirty_status" ]]; then
  echo "FR-000: precondition failed — working tree is not clean" >&2
  echo "  → Examined: the full working tree, including submodules whose .gitmodules" >&2
  echo "    entry sets 'ignore = untracked' (this guard passes --ignore-submodules=none" >&2
  echo "    precisely so those are not excluded from the corpus)." >&2
  echo "  → Not clean:" >&2
  printf '%s\n' "$dirty_status" | sed 's/^/      /' >&2
  echo "  → Do: commit, stash, or remove the entries above. If an entry is a submodule" >&2
  echo "    shown as ' M submodules/<name>', inspect it with:" >&2
  echo "      git -C \"$REPO_PATH\" status --porcelain --ignore-submodules=none" >&2
  echo "      git -C \"$REPO_PATH/submodules/<name>\" status --porcelain" >&2
  echo "    An untracked leftover inside such a submodule does NOT show in a plain" >&2
  echo "    'git status' — that asymmetry is the defect this guard now closes." >&2
  exit 2
fi

echo "FR-000: precondition satisfied — on master, working tree clean"
exit 0
