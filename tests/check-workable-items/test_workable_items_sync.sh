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

# --------------------------------------------------------------------------
# Clause-3 falsifiability: the §11.4.95 DB-tracked assertion (lines ~48-51 of
# the gate) MUST reject an UNTRACKED DB. Without this sub-test the entire
# git-tracking branch was uncovered — a refactor could delete it and CI would
# stay green (proven 2026-06-09 wave-6 bluff hunt: removing clause 3 left the
# old test reporting PASS). We exercise it hermetically by pointing the gate's
# overridable DB/ISSUES/FIXED paths at a fixture DB inside a temp git repo
# where the DB is deliberately NOT git-added.
echo "  clause-3 (DB-tracked) falsifiability:"
FIXTURE_DIR="$(mktemp -d)"
cleanup_fixture() { rm -rf "$FIXTURE_DIR"; }
trap cleanup_fixture EXIT

(
  cd "$FIXTURE_DIR"
  git init -q .
  git config user.email t@example.com
  git config user.name t
  mkdir -p docs
  # Seed an in-sync DB ↔ Markdown pair so validate + diff pass, isolating the
  # tracked-check as the only failing condition.
  WI_BIN_ABS="$ROOT/constitution/scripts/workable-items/bin/workable-items"
  "$WI_BIN_ABS" add Task low --db docs/wi.db --title "fixture" --description "hermetic fixture item created solely to exercise the clause-3 git-tracking assertion of the workable-items gate" --prefix LVA >/dev/null
  "$WI_BIN_ABS" sync db-to-md --db docs/wi.db --out-issues docs/Issues.md --out-fixed docs/Fixed.md >/dev/null
  # Track the Markdown but deliberately leave docs/wi.db UNTRACKED.
  git add docs/Issues.md docs/Fixed.md
  git commit -q -m "fixture: tracked markdown, untracked db"
)

# Point the gate at the untracked fixture DB. Expect FAIL (clause 3).
if LAVA_WORKABLE_ITEMS_DB="$FIXTURE_DIR/docs/wi.db" \
   LAVA_WORKABLE_ITEMS_ISSUES="$FIXTURE_DIR/docs/Issues.md" \
   LAVA_WORKABLE_ITEMS_FIXED="$FIXTURE_DIR/docs/Fixed.md" \
   bash "$GATE" >/dev/null 2>&1; then
  echo "FAIL: gate accepted an UNTRACKED DB (clause-3 §11.4.95 not enforced)" >&2
  exit 1
fi
echo "    untracked DB correctly REJECTED"
# Positive control for clause 3 (tracked DB → PASS) is already covered by the
# top-level positive case above, which runs against the real, git-tracked
# docs/workable_items.db. The gate evaluates git-tracking from the Lava repo
# cwd, so a DB tracked only inside the fixture repo is (correctly) seen as
# untracked from Lava — which is exactly why the negative assertion above is
# the meaningful, branch-covering check.

echo "PASS: CM-WORKABLE-ITEMS-SYNC gate is falsifiable"
