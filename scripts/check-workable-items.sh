#!/usr/bin/env bash
# check-workable-items.sh — CM-WORKABLE-ITEMS-SYNC gate
#
# §11.4.93 + §11.4.95: Lava tracks workable items via the CANONICAL
# workable-items Go binary in the constitution submodule
# (constitution/scripts/workable-items/), keyed LVA-N. The SQLite SSoT
# at docs/workable_items.db (TRACKED in git, §11.4.95) MUST stay in sync
# with the generated Markdown trackers (docs/Issues.md, docs/Fixed.md).
#
# This gate:
#   1. builds the canonical binary if absent (from inside the module dir;
#      CGO_ENABLED=1 is required — cgo sqlite driver);
#   2. runs `validate` (closed-set + §11.4.91 invariants);
#   3. runs `diff` (exit 1 on DB↔Markdown divergence — CM-WORKABLE-ITEMS-MD-DB-IN-SYNC);
#   4. asserts docs/workable_items.db is git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED).
#
# Replaces the retired CM-LVA-TICKETS-SYNC gate (bespoke tools/lava-tickets/
# system, migrated 2026-05-31 per docs/tickets/MIGRATION-TO-CANONICAL.md).
#
# Exit 0 = in sync + tracked; exit 1 = divergence / build failure / DB untracked.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODULE_DIR="constitution/scripts/workable-items"
WI_BIN="$MODULE_DIR/bin/workable-items"
DB="docs/workable_items.db"
ISSUES="docs/Issues.md"
FIXED="docs/Fixed.md"

# Build the canonical binary if absent (must cd into the module dir; no root go.mod).
if [[ ! -x "$WI_BIN" ]]; then
  ( cd "$MODULE_DIR" && CGO_ENABLED=1 go build -o bin/workable-items ./cmd/workable-items )
fi

# 1. Validate DB invariants (closed sets + §11.4.91 floor).
"$WI_BIN" validate --db "$DB"

# 2. DB ↔ Markdown sync (CM-WORKABLE-ITEMS-MD-DB-IN-SYNC). `diff` exits 1 on divergence.
if ! "$WI_BIN" diff --db "$DB" --issues "$ISSUES" --fixed "$FIXED"; then
  echo "CM-WORKABLE-ITEMS-SYNC: $ISSUES/$FIXED are stale — regenerate via:" >&2
  echo "  $WI_BIN sync db-to-md --db $DB --out-issues $ISSUES --out-fixed $FIXED" >&2
  exit 1
fi

# 3. §11.4.95 — the DB MUST be tracked in git (never gitignored).
if ! git ls-files --error-unmatch "$DB" >/dev/null 2>&1; then
  echo "CM-WORKABLE-ITEMS-SYNC: $DB is NOT git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED) — run: git add $DB" >&2
  exit 1
fi

echo "CM-WORKABLE-ITEMS-SYNC: OK — $DB validated, DB ↔ Markdown in sync, DB tracked"
