#!/usr/bin/env bash
# finish-migration.sh — completes + verifies the LVA-3 canonical migration.
# Idempotent: rebuilds the canonical DB from scratch, applies all statuses,
# regenerates docs, validates, diffs, runs the new gate + hermetic test.
# Run from repo root: bash docs/tickets/finish-migration.sh
# NO git commit/push — staging is the main agent's job.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

WI=constitution/scripts/workable-items/bin/workable-items
DB=docs/workable_items.db

[[ -x "$WI" ]] || ( cd constitution/scripts/workable-items && CGO_ENABLED=1 go build -o bin/workable-items ./cmd/workable-items )

rm -f "$DB" "$DB-wal" "$DB-shm"

"$WI" add Bug P1 --db "$DB" --id LVA-1 \
  --title "Deflake CredentialsViewModelTest > select provider updates selectedProvider" \
  --description "67th-cycle full-suite flaky test (fixed-awaitState-count vs Room Flow .first() off the StandardTestDispatcher). Fixed in the 68th cycle (Commit 1) via bounded await-until-selectedProvider loop; falsifiability-rehearsed. **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-flaky-credentialsviewmodeltest.json"
"$WI" add Task P1 --db "$DB" --id LVA-2 \
  --title "§6.X-debt darwin/arm64 emulator-acceleration sub-debt" \
  --description "Per-OS emulator acceleration (Containers c1871138 + 6aff7ea8): macOS gate runner is host-direct+HVF. PROVEN by C00 cold-start canary + full 37-class Challenge suite on Pixel_8/API35 (43 pass / 3 credential-skip / 0 fail). RESOLVED 2026-05-20. **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json"
"$WI" add Task P1 --db "$DB" --id LVA-3 \
  --title "Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind)" \
  --description "Pin 208e2c8 is 53 commits behind origin/main 883ccc1. Highest-impact new clauses: §11.4.93/95/106 (workable-items SQLite DB tracked in git + md to DB sync engine), §11.4.79 (own-org submodules in CodeGraph), §11.4.85 (stress/chaos), §11.4.98, §11.4.102. Pin-bump is operator-gated; decision owed on §6.AD.3 Path B vs SQLite DB. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md"
"$WI" add Feature P1 --db "$DB" --id LVA-4 \
  --title "LVA workable-items ticket system (SQLite DB + Go CLI + md/HTML/PDF/DOCX export)" \
  --description "HelixConstitution §11.4.93/95/106 materialization. Go CLI (modernc.org/sqlite, no CGO) with init/add/update/close/reopen/gen/verify/import/export. Operator directive §6.L 68th invocation, key prefix LVA. Superseded by migration to the canonical constitution binary (docs/tickets/MIGRATION-TO-CANONICAL.md). **Source:** operator-report — docs/tickets/DESIGN.md"
"$WI" add Bug P0 --db "$DB" --id LVA-5 \
  --title "Rotate Firebase CI token (printed to session transcript)" \
  --description "67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json"
"$WI" add Task P2 --db "$DB" --id LVA-6 \
  --title "§11.4.79 reconcile codegraph index policy (own-org submodules IN index)" \
  --description "§11.4.79 (new) requires own-org submodules IN the codegraph index; Lava currently EXCLUDES submodules/ per docs/CODEGRAPH.md + 63rd-cycle policy. Reconcile .codegraph config + docs when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md"
"$WI" add Task P2 --db "$DB" --id LVA-7 \
  --title "§11.4.85 stress + chaos test scaffold" \
  --description "§11.4.85 (new universal anchor) mandates a stress + chaos test class. Lava has no chaos/stress suite today. Assess + scaffold when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md"

"$WI" close LVA-1 --db "$DB" --status fixed \
  --evidence feature/credentials/src/test/kotlin/lava/feature/credentials/CredentialsViewModelTest.kt
"$WI" close LVA-2 --db "$DB" --status completed \
  --evidence .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json

# Discover the exact Status-line literal the generator emits so the body_md
# patch matches it (robust against "- **Status:**" vs "**Status:**" formats).
"$WI" sync db-to-md --db "$DB" --out-issues docs/Issues.md --out-fixed docs/Fixed.md
STATUS_LINE_PREFIX="$(grep -m1 -oE '[-* ]*\*\*Status:\*\*' docs/Issues.md || echo '**Status:**')"
echo "detected status-line prefix: [$STATUS_LINE_PREFIX]"

# DB-direct status transitions (no update subcommand). Patch BOTH the status
# column AND the rendered Status line in body_md using the detected prefix.
sqlite3 "$DB" "UPDATE items SET status='In progress', last_modified=datetime('now'),
  body_md=replace(body_md, '${STATUS_LINE_PREFIX} Queued', '${STATUS_LINE_PREFIX} In progress')
  WHERE atm_id IN ('LVA-3','LVA-4');"
sqlite3 "$DB" "UPDATE items SET status='Operator-blocked', last_modified=datetime('now'),
  body_md=replace(body_md, '${STATUS_LINE_PREFIX} Queued', '${STATUS_LINE_PREFIX} Operator-blocked')
  WHERE atm_id='LVA-5';"
sqlite3 "$DB" "INSERT OR REPLACE INTO operator_block_details(atm_id,what,why_exhausted_alternatives,unblock_condition,who)
  VALUES('LVA-5',
    'RuTracker CI Firebase token rotation',
    'Agent cannot rotate credentials; only the operator can run firebase logout / firebase login:ci',
    'Operator runs firebase logout then firebase login:ci and updates LAVA_FIREBASE_TOKEN in gitignored .env',
    'Operator');"

# Regenerate AFTER all status changes, then validate + diff.
"$WI" sync db-to-md --db "$DB" --out-issues docs/Issues.md --out-fixed docs/Fixed.md
sqlite3 "$DB" "PRAGMA wal_checkpoint(TRUNCATE);"
echo "=== validate ==="; "$WI" validate --db "$DB"
echo "=== diff (expect in sync, exit 0) ==="; "$WI" diff --db "$DB" --issues docs/Issues.md --fixed docs/Fixed.md
echo "=== report ==="; "$WI" report --db "$DB" --by-status
echo "=== final statuses ==="; sqlite3 "$DB" "SELECT atm_id||'|'||status||'|'||current_location FROM items ORDER BY atm_id;"

echo "=== gate (after intent-staging the DB so the §11.4.95 tracked-check passes) ==="
git add -N "$DB"
set +e
bash scripts/check-workable-items.sh; echo "gate_exit=$?"
bash tests/check-workable-items/test_workable_items_sync.sh; echo "test_exit=$?"
set -e
git reset -q "$DB"
echo "DONE — review output above; if validate OK + diff in-sync + gate PASS, the migration is complete. Then main agent: git add -A"
