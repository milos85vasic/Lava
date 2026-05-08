#!/usr/bin/env bash
# scripts/sync-tracker-sdk-mirrors.sh
# Pushes the Submodules/Tracker-SDK/ working tree to its 2 configured upstreams
# (GitHub + GitLab). Local-only — no hosted CI invoked. Operator-controlled.
#
# Only GitHub + GitLab per constitutional clause §6.W (added 2026-05-08).

set -euo pipefail

cd "$(dirname "$0")/../Submodules/Tracker-SDK"

UPSTREAMS=(
  "github  git@github.com:vasic-digital/Tracker-SDK.git"
  "gitlab  git@gitlab.com:vasic-digital/Tracker-SDK.git"
)

# Ensure remotes exist (idempotent)
for entry in "${UPSTREAMS[@]}"; do
  read -r name url <<<"$entry"
  if ! git remote get-url "$name" >/dev/null 2>&1; then
    git remote add "$name" "$url"
  else
    git remote set-url "$name" "$url"
  fi
done

declare -A SHAS
for entry in "${UPSTREAMS[@]}"; do
  read -r name url <<<"$entry"
  echo ">>> Pushing to $name ($url)..."
  git push "$name" --tags --force-with-lease
  git push "$name" master --force-with-lease
  SHAS[$name]=$(git ls-remote "$name" master | awk '{print $1}')
done

# Verify per-mirror SHA convergence (Sixth Law clause 6.C)
EXPECTED=$(git rev-parse master)
for entry in "${UPSTREAMS[@]}"; do
  read -r name _ <<<"$entry"
  if [[ "${SHAS[$name]}" != "$EXPECTED" ]]; then
    echo "MIRROR MISMATCH: $name reports ${SHAS[$name]}, expected $EXPECTED" >&2
    exit 1
  fi
done

echo "All upstreams converged on $EXPECTED"
