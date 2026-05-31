#!/usr/bin/env bash
# test_workable_items_sync.sh — hermetic falsifiability test for CM-WORKABLE-ITEMS-SYNC
#
# Runs the gate on the real tree (expect PASS), then corrupts docs/Issues.md
# and re-runs the gate (expect FAIL via `diff` divergence). Restores. Then
# confirms the gate fails when the DB is conceptually untracked is covered by
# the live git-tracking assertion. Exit 0 = both behaviours correct.
#
# This replaces tests/check-lva-tickets/test_lva_tickets_sync.sh (retired
# bespoke lava-tickets system, migrated 2026-05-31).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

GATE="scripts/check-workable-items.sh"

echo "test: CM-WORKABLE-ITEMS-SYNC falsifiability"

# Positive: gate passes on a clean, in-sync tree.
if ! bash "$GATE"; then
  echo "FAIL: gate rejected a clean tree" >&2
  exit 1
fi
echo "  positive case: PASS"

# Negative: corrupt Issues.md, expect gate to fail (diff divergence).
CORRUPT_TARGET="docs/Issues.md"
BACKUP="$(mktemp)"
cp "$CORRUPT_TARGET" "$BACKUP"
printf '\n<!-- DELIBERATE CORRUPTION -->\n' >> "$CORRUPT_TARGET"
if bash "$GATE" >/dev/null 2>&1; then
  cp "$BACKUP" "$CORRUPT_TARGET"
  rm -f "$BACKUP"
  echo "FAIL: gate accepted a corrupted tracker" >&2
  exit 1
fi
cp "$BACKUP" "$CORRUPT_TARGET"
rm -f "$BACKUP"
echo "  negative case (corrupted Issues.md): gate correctly FAILED"

# Confirm restoration leaves the gate green again.
if ! bash "$GATE"; then
  echo "FAIL: gate did not recover after restoring Issues.md" >&2
  exit 1
fi

echo "PASS: CM-WORKABLE-ITEMS-SYNC gate is falsifiable"
