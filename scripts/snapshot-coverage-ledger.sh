#!/usr/bin/env bash
# scripts/snapshot-coverage-ledger.sh — LVA-019 / §11.4.25 per-release ledger snapshot.
#
# Regenerates docs/coverage-ledger.yaml (via scripts/generate-coverage-ledger.sh)
# and freezes a copy under .lava-ci-evidence/coverage-ledger-snapshots/, mirroring
# the existing .lava-ci-evidence/distribute-changelog/<channel>/cycle-coverage-map-
# <version>-<code>.yaml per-release snapshot convention already used by
# scripts/firebase-distribute.sh for the §6.AK device-coverage gate.
#
# Usage:
#   scripts/snapshot-coverage-ledger.sh <version>-<code>
#
# Example:
#   scripts/snapshot-coverage-ledger.sh 1.3.17-1085
#     -> writes .lava-ci-evidence/coverage-ledger-snapshots/1.3.17-1085.yaml

set -euo pipefail

VERSION_CODE_TAG="${1:?Usage: scripts/snapshot-coverage-ledger.sh <version>-<code>}"

# Guard: the tag is interpolated straight into a FILENAME under $SNAPSHOT_DIR,
# so accept only a <version>-<code> shape. Without this guard a mis-invocation
# such as `snapshot-coverage-ledger.sh --check` is silently accepted as a
# version tag and writes a junk `--check.yaml` snapshot — which happened on
# 2026-08-27 and reached commit 38986527 before being removed.
if [[ ! "$VERSION_CODE_TAG" =~ ^[0-9]+(\.[0-9]+)*-[0-9]+$ ]]; then
    echo "ERROR: expected <version>-<code> (e.g. 1.3.17-1085), got '$VERSION_CODE_TAG'" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SNAPSHOT_DIR=".lava-ci-evidence/coverage-ledger-snapshots"
SNAPSHOT_PATH="$SNAPSHOT_DIR/$VERSION_CODE_TAG.yaml"
LEDGER_PATH="docs/coverage-ledger.yaml"

# Was the tracked ledger clean before we touched it? If so, we're responsible
# for leaving it clean afterward too — this script takes a snapshot, it does
# not mutate tracked state.
LEDGER_WAS_CLEAN=0
if [[ -z "$(git status --porcelain "$LEDGER_PATH" 2>/dev/null)" ]]; then
    LEDGER_WAS_CLEAN=1
fi

echo "==> LVA-019: regenerating docs/coverage-ledger.yaml"
bash scripts/generate-coverage-ledger.sh --quiet

mkdir -p "$SNAPSHOT_DIR"
cp "$LEDGER_PATH" "$SNAPSHOT_PATH"

if [[ "$LEDGER_WAS_CLEAN" -eq 1 ]] && [[ -n "$(git status --porcelain "$LEDGER_PATH" 2>/dev/null)" ]]; then
    git checkout -- "$LEDGER_PATH"
fi

echo "==> Snapshot written: $SNAPSHOT_PATH"
exit 0
