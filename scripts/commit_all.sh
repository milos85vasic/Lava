#!/usr/bin/env bash
# scripts/commit_all.sh — thin commit + multi-mirror-push wrapper.
#
# Forensic anchor: HelixConstitution submodule (./constitution) mandates a
# project-official commit wrapper per its CLAUDE.md "MANDATORY COMMIT &
# PUSH CONSTRAINTS" section: every push fans out to ALL configured upstream
# remotes; no `git add` / `git commit` / `git push` on the main repo unless
# the project Constitution explicitly carves out a use case.
#
# Lava's §6.W mandate restricts the parent repo + every Lava-owned submodule
# to GitHub + GitLab only (CLI parity requirement). HelixConstitution itself
# uses 4 upstreams (GitHub + GitLab + GitFlic + GitVerse) via its own
# install_upstreams.sh; that submodule's rule is per-submodule, not parent.
#
# Usage:
#
#   scripts/commit_all.sh -m "commit message"           # commit + push to both
#   scripts/commit_all.sh -m "msg" --no-push            # commit only, no push
#   scripts/commit_all.sh -m "msg" --files file1 file2  # add only specified
#   scripts/commit_all.sh --amend                       # amend previous (DANGEROUS)
#   scripts/commit_all.sh --status                      # diagnostic
#
# Inheritance from HelixConstitution:
#   - §11.4.22 lightweight commit-path discipline (this is the wrapper)
#   - §11.4 anti-bluff: refuses to push if pre-push hook fails (no --no-verify)
#   - §9 absolute-data-safety: refuses --amend without explicit operator
#     authorization (the script aborts with a directive to use git directly
#     after acknowledging the safety implications)
#
# Exit codes:
#   0 — committed + pushed (or committed only with --no-push)
#   1 — usage error
#   2 — git operation failed
#   3 — nothing to commit (informational, not error)
#   4 — push failed for one or more mirrors
#   5 — operator confirmation required for destructive flag

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

# Default behavior
MESSAGE=""
PUSH=true
AMEND=false
STATUS_ONLY=false
declare -a FILES=()
ALL_FILES=true

usage() {
    sed -n '3,30p' "$0"
    exit 1
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -m|--message)
            MESSAGE="${2:-}"
            shift 2
            ;;
        --no-push)
            PUSH=false
            shift
            ;;
        --files)
            ALL_FILES=false
            shift
            while [[ $# -gt 0 && ! "$1" =~ ^- ]]; do
                FILES+=("$1")
                shift
            done
            ;;
        --amend)
            AMEND=true
            shift
            ;;
        --status)
            STATUS_ONLY=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            usage
            ;;
    esac
done

if [[ "${STATUS_ONLY}" == "true" ]]; then
    echo "==> repo: ${REPO_ROOT}"
    echo "==> branch: $(git rev-parse --abbrev-ref HEAD)"
    echo "==> HEAD: $(git rev-parse HEAD)"
    echo "==> remotes:"
    git remote -v | sed 's/^/    /'
    echo "==> dirty files:"
    git status --short | head -20 | sed 's/^/    /'
    exit 0
fi

if [[ "${AMEND}" == "true" ]]; then
    echo "ERROR: --amend is destructive (rewrites the previous commit)." >&2
    echo "       Per HelixConstitution §9, destructive operations require explicit" >&2
    echo "       per-operation operator authorization. Use \`git commit --amend\`" >&2
    echo "       directly after backing up .git." >&2
    exit 5
fi

if [[ -z "${MESSAGE}" ]]; then
    echo "ERROR: -m '<message>' required" >&2
    usage
fi

# Stage files
if [[ "${ALL_FILES}" == "true" ]]; then
    git add -A
else
    git add "${FILES[@]}"
fi

# Commit
if git diff --cached --quiet; then
    echo "==> nothing staged — skipping commit"
    exit 3
fi

if ! git commit -m "${MESSAGE}"; then
    echo "ERROR: git commit failed" >&2
    exit 2
fi

NEW_HEAD="$(git rev-parse HEAD)"
echo "==> committed: ${NEW_HEAD}"

if [[ "${PUSH}" == "false" ]]; then
    echo "==> --no-push specified; skipping push"
    exit 0
fi

# Push to every configured non-origin remote (per §6.W: github + gitlab only)
PUSH_FAILED=false
declare -a REMOTES=()
while IFS= read -r remote; do
    case "${remote}" in
        github|gitlab) REMOTES+=("${remote}") ;;
    esac
done < <(git remote)

if [[ ${#REMOTES[@]} -eq 0 ]]; then
    echo "WARN: no github/gitlab remotes configured — nothing to push" >&2
    exit 0
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
for remote in "${REMOTES[@]}"; do
    echo "==> pushing to ${remote}/${BRANCH}"
    if ! git push "${remote}" "${BRANCH}"; then
        echo "ERROR: push to ${remote} failed" >&2
        PUSH_FAILED=true
    fi
done

if [[ "${PUSH_FAILED}" == "true" ]]; then
    exit 4
fi

# §6.C convergence check
echo ""
echo "==> §6.C convergence check"
LOCAL="$(git rev-parse HEAD)"
ALL_OK=true
for remote in "${REMOTES[@]}"; do
    REMOTE_SHA="$(git ls-remote "${remote}" "${BRANCH}" | cut -f1)"
    if [[ "${LOCAL}" == "${REMOTE_SHA}" ]]; then
        echo "    ${remote}: ✓ ${REMOTE_SHA}"
    else
        echo "    ${remote}: ✗ ${REMOTE_SHA} (expected ${LOCAL})" >&2
        ALL_OK=false
    fi
done

if [[ "${ALL_OK}" == "false" ]]; then
    echo "ERROR: §6.C divergence detected" >&2
    exit 4
fi

echo ""
echo "✓ commit + push complete"
