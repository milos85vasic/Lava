# LVA-3 Migration to canonical workable-items — EXECUTION LOG (COMPLETE + VERIFIED)

**Executed:** 2026-05-31 by execution subagent (LVA-3, per `docs/tickets/MIGRATION-TO-CANONICAL.md` rev 1).
**Outcome:** COMPLETE. Lava ticket tracking migrated from the bespoke `tools/lava-tickets/` system to the **canonical** `workable-items` Go binary (`constitution/scripts/workable-items/`), keyed `LVA-N`. All 7 LVA keys preserved verbatim. New gate `CM-WORKABLE-ITEMS-SYNC` passes; falsifiability rehearsed.
**§11.4.6 (no-guessing):** every result below is real captured command output. Two early missteps are recorded honestly: (a) a first seed pass used placeholder titles/bodies and was DISCARDED + rebuilt with verbatim real data; (b) the first `body_md` status-patch used the wrong Status-line literal, which the finisher fixed via auto-detection of the generator's exact prefix.
**NO git commit/push performed** — the main agent stages with `git add -A` (exact list in §9).

---

## §1 — Build the canonical binary (VERIFIED)
```
( cd constitution/scripts/workable-items && CGO_ENABLED=1 GOMAXPROCS=2 nice -n 19 go build -o bin/workable-items ./cmd/workable-items )
```
→ **EXIT 0**, binary = **7,437,170 bytes**. (`bin/` is gitignored in the submodule — not a tracked change.) `CGO_ENABLED=1` is required; root `go build` fails (no root go.mod).

## §2 — Read the old DB (VERIFIED — 7 rows, NOT 8)
`sqlite3 docs/tickets/tickets.db "SELECT COUNT(*) FROM tickets;"` → **7**. No LVA-8 exists in this checkout.
Verbatim real rows captured (titles/bodies/provenance):

| id | LVA type | LVA status/closure | prio | title (verbatim) |
|---|---|---|---|---|
| LVA-1 | Bug | Closed/Fixed | P1 | Deflake CredentialsViewModelTest > select provider updates selectedProvider |
| LVA-2 | Debt | Closed/Closed | P1 | §6.X-debt darwin/arm64 emulator-acceleration sub-debt |
| LVA-3 | Task | InProgress | P1 | Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind) |
| LVA-4 | Feature | InProgress | P1 | LVA workable-items ticket system (SQLite DB + Go CLI + md/HTML/PDF/DOCX export) |
| LVA-5 | Incident | OperatorBlocked | P0 | Rotate Firebase CI token (printed to session transcript) |
| LVA-6 | Task | Open | P2 | §11.4.79 reconcile codegraph index policy (own-org submodules IN index) |
| LVA-7 | Task | Open | P2 | §11.4.85 stress + chaos test scaffold |

Real LVA-5 operator_blocked_details (verbatim): "Operator must run firebase logout then firebase login:ci and update LAVA_FIREBASE_TOKEN in gitignored .env. Agent cannot rotate credentials."

## §3 — Seed canonical DB + statuses (VERIFIED)
`rm -f docs/workable_items.db{,-wal,-shm}`, 7× `add --id LVA-N` (all rc 0), type-mapping per plan §3 (**LVA-2 Debt→Task, LVA-5 Incident→Bug**; severity = P-level). Closes via `close`; non-terminal statuses via DB-direct UPDATE (no `update` subcommand). The repeatable seed+verify script is committed at `docs/tickets/finish-migration.sh` (idempotent — rebuilds the DB from scratch, auto-detects the generator's Status-line literal, regenerates, validates, diffs, runs the gate+test).

**FINAL canonical state (matches plan §3 target table EXACTLY):**
```
LVA-1 | Bug     | Fixed (→ Fixed.md)     | P1 | Fixed
LVA-2 | Task    | Completed (→ Fixed.md) | P1 | Fixed
LVA-3 | Task    | In progress           | P1 | Issues
LVA-4 | Feature | In progress           | P1 | Issues
LVA-5 | Bug     | Operator-blocked      | P0 | Issues
LVA-6 | Task    | Queued                | P2 | Issues
LVA-7 | Task    | Queued                | P2 | Issues
```
**LVA-N keys stored verbatim (no zero-padding) — VERIFIED.** Closes recorded real evidence paths:
- LVA-1 → `feature/credentials/src/test/kotlin/lava/feature/credentials/CredentialsViewModelTest.kt`
- LVA-2 → `.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json`

LVA-5 `operator_block_details` row inserted (what / why-exhausted / unblock-condition / who=Operator), reflecting the Firebase-token-rotation operator-block.

## §4 — Generate docs + validate + diff (VERIFIED)
```
sync db-to-md --out-issues docs/Issues.md --out-fixed docs/Fixed.md   → wrote docs/Issues.md + docs/Fixed.md
validate                                                              → validate: OK — 7 items, all invariants satisfied
diff --issues docs/Issues.md --fixed docs/Fixed.md                    → diff: DB and Markdown are in sync   [exit 0]
report --by-status                                                    → In progress 2 | Queued 2 | Completed 1 | Fixed 1 | Operator-blocked 1 | TOTAL 7
PRAGMA wal_checkpoint(TRUNCATE)                                       → WAL collapsed
```
**Output paths CONFIRMED from the binary:** `docs/Issues.md` (LVA-3/4/5/6/7) + `docs/Fixed.md` (LVA-1/2) at the docs ROOT. The binary does NOT emit `_Summary.md`/HTML/PDF/DOCX (those were `tools/lava-tickets/export.go`/`render.go` only — see §7 OWED). A stale, INCORRECT `docs/Issues_Summary.md` (+`Fixed_Summary.md`) leftover from an earlier generation (wrong data: LVA-4 Task vs Feature, LVA-5 P1 vs P0) was REMOVED so no stale summary ships (plan §3 Step 3).

## §5 — Gate replacement (CM-LVA-TICKETS-SYNC → CM-WORKABLE-ITEMS-SYNC) (VERIFIED)
`grep -rl` located the wiring (the plan's `UNCONFIRMED` location):
- gate: `scripts/check-lva-tickets.sh`
- sweep: `scripts/verify-all-constitution-rules.sh` **lines 189–193** (`run_gate "lva-tickets-sync" ... "bash scripts/check-lva-tickets.sh --advisory"`)
- test: `tests/check-lva-tickets/test_check_lva_tickets.sh` (+ `EVIDENCE.md`)
- gates-index: `docs/helix-constitution-gates.md` line 25
- script docs: `docs/scripts/check-lva-tickets.sh.md`, `docs/scripts/lava-tickets.md`
- (no `.githooks/pre-push` reference)

Actions:
- **Created** `scripts/check-workable-items.sh` (chmod +x) — canonical `validate` + `diff` (exit 1 on divergence) + `git ls-files --error-unmatch docs/workable_items.db` (§11.4.95). Builds binary if absent.
- **Created** `tests/check-workable-items/test_workable_items_sync.sh` (chmod +x) — hermetic falsifiability test.
- **Created** `docs/scripts/check-workable-items.sh.md` (§11.4.18 script doc — keeps CM-SCRIPT-DOCS-SYNC green).
- **Edited** `scripts/verify-all-constitution-rules.sh` lines 189–193 → `run_gate "workable-items-sync" "CM-WORKABLE-ITEMS-SYNC ..." "bash scripts/check-workable-items.sh"` (strict; DB+docs ship in-sync).
- **Edited** `docs/helix-constitution-gates.md` line 25 → new CM-WORKABLE-ITEMS-SYNC row.
- **Deleted** old gate + test + EVIDENCE + the 2 stale script docs.

## §6 — Gate run + falsifiability rehearsal (VERIFIED, real evidence)
Reversible probe (`git add -N docs/workable_items.db` … run … `git reset`; NO commit, DB content untouched):
```
GATE:  CM-WORKABLE-ITEMS-SYNC: OK — docs/workable_items.db validated, DB ↔ Markdown in sync, DB tracked   [gate_rc=0]
TEST:  positive case: PASS
       negative case (corrupted docs/Issues.md): gate correctly FAILED
       (restore) → PASS
       PASS: CM-WORKABLE-ITEMS-SYNC gate is falsifiable                                                   [test_rc=0]
```
Pre-staging (DB not yet tracked) the gate correctly FAILS with the §11.4.95 directive — proving the tracked-check works; it goes green once the main agent stages the DB.
`scripts/check-script-docs-sync.sh` → `✓ all 71 scripts documented; no orphan docs` (EXIT 0) after the doc add + stale-doc removals.

## §7 — Retire bespoke system (VERIFIED deleted via plain `rm`)
- `tools/lava-tickets/` (whole dir: `_seed_evidence.sh`, `commands.go`, `export.go`, `go.mod`, `go.sum`, `main.go`, `render.go`, `tickets_test.go`; `bin/` was gitignored).
- `docs/tickets/tickets.db`; `docs/tickets/{Issues,Fixed,Issues_Summary,Fixed_Summary}.md`; `docs/tickets/export/` (docx + html subtrees).
- `scripts/check-lva-tickets.sh`; `tests/check-lva-tickets/` (test + EVIDENCE.md); `docs/scripts/check-lva-tickets.sh.md`; `docs/scripts/lava-tickets.md`.
- Stray `docs/Issues_Summary.md` + `docs/Fixed_Summary.md` (non-canonical leftovers).

**KEPT under `docs/tickets/` as historical record** (per dispatch + plan §4 Step 4): `DESIGN.md`, `BUILD-EVIDENCE.md`, `MIGRATION-TO-CANONICAL.md`, `schema.sql`, this `MIGRATION-EXECUTED.md`, and `finish-migration.sh` (the repeatable seeder).
> Minor plan discrepancy: plan §4 Step 4 says delete `docs/tickets/schema.sql`; the dispatch said KEEP it. Honored the dispatch (KEPT). Main agent may delete if preferred — no longer load-bearing.

**OWED follow-ups (NOT blocking; file as future LVA items):**
- Canonical binary has no `update`/`reopen`/`block` subcommand — non-terminal/operator-blocked transitions used DB-direct UPDATE. Upstream-extend per §11.4.74 (constitution-submodule §11.4.26 7-step pipeline).
- Summaries (`*_Summary.md`) + HTML/PDF/DOCX exports are NOT emitted by the canonical binary. If still required, regenerate via the project export pipeline / §11.4.106 Docs Chain.
- Incident/Debt/Chore→Task coercion caveat + `source/source_ref` folded into description (documented in CLAUDE.md §6.AF + CONTINUATION by the main agent).
- `docs/chaos-stress/EXPORT-AUDIT.md` references the old lava-tickets export pipeline — main agent should update or mark historical.

## §8 — .gitignore (VERIFIED)
Replaced the dead `# lava-tickets build artifacts` block with:
```
# workable-items canonical tracker — DB is TRACKED (§11.4.95); only WAL/SHM sidecars are ignored
docs/workable_items.db-wal
docs/workable_items.db-shm
```
Verified: `git check-ignore docs/workable_items.db-{wal,shm}` → rc 0 (both ignored, correct); `git check-ignore docs/workable_items.db` → rc 1 / empty (**NOT ignored = correct, §11.4.95**). WAL/SHM sidecars checkpoint-collapsed + removed for a clean tree.

---

## §9 — Precise staging list for the main agent (`git add -A` captures all of this)

**CREATE (untracked `??`):**
- `docs/workable_items.db`  ← **§11.4.95 TRACKED — must be staged**
- `docs/Issues.md`
- `docs/Fixed.md`
- `scripts/check-workable-items.sh`
- `tests/check-workable-items/test_workable_items_sync.sh`
- `docs/scripts/check-workable-items.sh.md`
- `docs/tickets/MIGRATION-EXECUTED.md` (this file)
- `docs/tickets/finish-migration.sh` (repeatable seeder/verifier)

**MODIFY (`M`):**
- `.gitignore`
- `docs/helix-constitution-gates.md`
- `scripts/verify-all-constitution-rules.sh`

**DELETE (`D`):**
- `scripts/check-lva-tickets.sh`
- `tests/check-lva-tickets/test_check_lva_tickets.sh`, `tests/check-lva-tickets/EVIDENCE.md`
- `docs/scripts/check-lva-tickets.sh.md`, `docs/scripts/lava-tickets.md`
- `docs/tickets/tickets.db`
- `docs/tickets/Issues.md`, `docs/tickets/Fixed.md`, `docs/tickets/Issues_Summary.md`, `docs/tickets/Fixed_Summary.md`
- `docs/tickets/export/docx/{Issues,Fixed,Issues_Summary,Fixed_Summary}.docx`
- `docs/tickets/export/html/{Issues,Fixed,Issues_Summary,Fixed_Summary}.html`
- `tools/lava-tickets/{_seed_evidence.sh,commands.go,export.go,go.mod,go.sum,main.go,render.go,tickets_test.go}`

**KEPT untouched:** `docs/tickets/{DESIGN,BUILD-EVIDENCE,MIGRATION-TO-CANONICAL}.md`, `docs/tickets/schema.sql`.
**WAL/SHM sidecars:** removed; gitignored regardless.

> Note on `git add -A` vs the stray summaries: `docs/Issues_Summary.md` + `docs/Fixed_Summary.md` at the docs ROOT were untracked stray files; they were `rm`'d, so they will NOT be staged (they never were tracked).

## §10 — §6.S / §6.AF follow-ups OWED (main agent — no commit allowed in this subagent)
- `CLAUDE.md §6.AF`: rewrite to point at the canonical binary (`constitution/scripts/workable-items/`), DB `docs/workable_items.db`, docs `docs/Issues.md`/`docs/Fixed.md`, the `--id LVA-N` (preserve) / `--prefix LVA` (new) convention, the Incident→Bug / Debt→Task / Chore→Task mapping, and the new `CM-WORKABLE-ITEMS-SYNC` gate. Add `Catalogue-Check: reuse HelixDevelopment/HelixConstitution@<pin-sha> scripts/workable-items` + `Classification: project-specific`.
- `docs/CONTINUATION.md`: LVA-3 done; LVA-4 superseded-by-migration; new DB+doc paths; retired `tools/lava-tickets/`; the OWED follow-ups from §7.
