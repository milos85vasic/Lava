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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SNAPSHOT_DIR=".lava-ci-evidence/coverage-ledger-snapshots"
SNAPSHOT_PATH="$SNAPSHOT_DIR/$VERSION_CODE_TAG.yaml"

echo "==> LVA-019: regenerating docs/coverage-ledger.yaml"
bash scripts/generate-coverage-ledger.sh --quiet 2>/dev/null || bash scripts/generate-coverage-ledger.sh

mkdir -p "$SNAPSHOT_DIR"
cp docs/coverage-ledger.yaml "$SNAPSHOT_PATH"

echo "==> Snapshot written: $SNAPSHOT_PATH"
exit 0
