# check-workable-items.sh

**Constitution rule:** §11.4.93 (workable-items SQLite single-source-of-truth) + §11.4.95 (DB tracked-in-git, never gitignored). Gate ID: `CM-WORKABLE-ITEMS-SYNC`.

**Type:** Local CI gate (invoked by `scripts/verify-all-constitution-rules.sh`).

**Last verified:** 2026-08-20 (re-verified against `scripts/check-workable-items.sh` at HEAD — ledger-filtered `validate` step added; `diff`/`git-tracked` steps unchanged).

## What it does

Verifies Lava's workable-items tracker is internally consistent, using the **canonical** `workable-items` Go binary shipped in the constitution submodule (`constitution/scripts/workable-items/`), keyed `LVA-N`:

1. Builds the canonical binary if absent (from inside the module dir; `CGO_ENABLED=1` is required — cgo sqlite driver).
2. `workable-items validate --db docs/workable_items.db` — closed-set + §11.4.91 invariants, **filtered against the exemption ledger** at `docs/superpowers/specs/2026-08-20-workable-items-evidence-exemptions.md` (see "Exemption ledger" below). A violation whose `(atm_id, history_id)` is not listed in the ledger's machine-readable block still fails the gate.
3. `workable-items diff --db docs/workable_items.db --issues docs/Issues.md --fixed docs/Fixed.md` — exits 1 on DB↔Markdown divergence (CM-WORKABLE-ITEMS-MD-DB-IN-SYNC).
4. `git ls-files --error-unmatch docs/workable_items.db` — asserts the DB is git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED).

Replaced the retired `CM-LVA-TICKETS-SYNC` gate (bespoke `tools/lava-tickets/` system, migrated 2026-05-31 per `docs/tickets/MIGRATION-TO-CANONICAL.md`).

## Exemption ledger — added 2026-08-20

67 pre-existing `validate` violations (dated 2026-06-09, predating this gate's strict wiring) have no honest fabrication-free fix: 49 point at evidence files bulk-deleted by commit `56f2417795c9` with the workable-items binary offering no "mark historically deleted, narrative stands" mechanism; 2 (same item, two history rows) name a malformed path that never existed under either its literal or `..`-normalized form; 16 have closure narratives naming a test class/module but no single unambiguous still-existing file (only bare module directories match — using one as "evidence" would be a guess). Two further violations (LVA-013 history ids 202/203) genuinely had two still-existing, unambiguous evidence files embedded in their own narrative and were FIXED via `correct-history-evidence`, not exempted.

Per the same §6.D Behavioral Coverage Contract precedent as `docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`: every exemption is a named, dated, individually-investigated row — never a blanket waiver. `LAVA_WORKABLE_ITEMS_EXEMPTIONS` overrides the ledger path for hermetic testing. Full investigation + per-category reasoning: `docs/superpowers/specs/2026-08-20-workable-items-evidence-exemptions.md`.

Falsifiability rehearsal (this session): removed the `LVA-009|16` line from the ledger → gate correctly FAILED with `CM-WORKABLE-ITEMS-SYNC: unexempted validate violation(s)`; restored the line → gate correctly PASSED again. `docs/workable_items.db` was independently confirmed unaffected by the rehearsal (`git diff` showed zero bytes changed on the DB across the mutate/restore cycle).

## Usage

```bash
bash scripts/check-workable-items.sh
```

To regenerate the trackers after editing the DB:
```bash
constitution/scripts/workable-items/bin/workable-items sync db-to-md \
  --db docs/workable_items.db --out-issues docs/Issues.md --out-fixed docs/Fixed.md
```

## Exit codes

- `0` — DB validated, DB↔Markdown in sync, DB tracked.
- `1` — validation failure, divergence, build failure, or DB untracked.

## Hermetic test

`tests/check-workable-items/test_workable_items_sync.sh` — positive case (clean tree PASS), negative case (corrupt `docs/Issues.md` → gate FAILS), restore → PASS.

## Inheritance

Classification: project-specific (the LVA key + Lava's doc set are Lava-specific; the §11.4.93/95 mandates are universal per HelixConstitution).
