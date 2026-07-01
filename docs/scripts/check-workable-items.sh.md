# check-workable-items.sh

**Constitution rule:** §11.4.93 (workable-items SQLite single-source-of-truth) + §11.4.95 (DB tracked-in-git, never gitignored). Gate ID: `CM-WORKABLE-ITEMS-SYNC`.

**Type:** Local CI gate (invoked by `scripts/verify-all-constitution-rules.sh`).

**Last verified:** 2026-07-01 (re-verified against `scripts/check-workable-items.sh` at HEAD — canonical-binary build + `validate`/`diff`/`git-tracked` steps below match the script's four-step flow).

## What it does

Verifies Lava's workable-items tracker is internally consistent, using the **canonical** `workable-items` Go binary shipped in the constitution submodule (`constitution/scripts/workable-items/`), keyed `LVA-N`:

1. Builds the canonical binary if absent (from inside the module dir; `CGO_ENABLED=1` is required — cgo sqlite driver).
2. `workable-items validate --db docs/workable_items.db` — closed-set + §11.4.91 invariants.
3. `workable-items diff --db docs/workable_items.db --issues docs/Issues.md --fixed docs/Fixed.md` — exits 1 on DB↔Markdown divergence (CM-WORKABLE-ITEMS-MD-DB-IN-SYNC).
4. `git ls-files --error-unmatch docs/workable_items.db` — asserts the DB is git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED).

Replaced the retired `CM-LVA-TICKETS-SYNC` gate (bespoke `tools/lava-tickets/` system, migrated 2026-05-31 per `docs/tickets/MIGRATION-TO-CANONICAL.md`).

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
