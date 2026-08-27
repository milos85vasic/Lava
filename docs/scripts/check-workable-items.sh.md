# check-workable-items.sh

**Constitution rule:** §11.4.93 (workable-items SQLite single-source-of-truth) + §11.4.95 (DB tracked-in-git, never gitignored). Gate ID: `CM-WORKABLE-ITEMS-SYNC`.

**Type:** Local CI gate (invoked by `scripts/verify-all-constitution-rules.sh`).

**Last verified:** 2026-08-26 (corpus-scope floor — both `_Summary` trackers are now regenerated from the DB and byte-compared; LVA vacuous-pass sweep F17)

## What it does

Verifies Lava's workable-items tracker is internally consistent, using the **canonical** `workable-items` Go binary shipped in the constitution submodule (`constitution/scripts/workable-items/`), keyed `LVA-N`:

1. Builds the canonical binary if absent (from inside the module dir; `CGO_ENABLED=1` is required — cgo sqlite driver).
2. `workable-items validate --db docs/workable_items.db` — closed-set + §11.4.91 invariants, **filtered against the exemption ledger** at `docs/superpowers/specs/2026-08-20-workable-items-evidence-exemptions.md` (see "Exemption ledger" below). A violation whose `(atm_id, history_id)` is not listed in the ledger's machine-readable block still fails the gate.
3. `workable-items diff --db docs/workable_items.db --issues docs/Issues.md --fixed docs/Fixed.md` — exits 1 on DB↔Markdown divergence (CM-WORKABLE-ITEMS-MD-DB-IN-SYNC).
4. **Summary-tracker byte comparison (added 2026-08-26).** Regenerates `Issues_Summary.md` + `Fixed_Summary.md` from the *same* DB into a temp dir and compares them byte-for-byte against the committed files. See "Corpus-scope floor" below.
5. `git ls-files --error-unmatch docs/workable_items.db` — asserts the DB is git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED).

Replaced the retired `CM-LVA-TICKETS-SYNC` gate (bespoke `tools/lava-tickets/` system, migrated 2026-05-31 per `docs/tickets/MIGRATION-TO-CANONICAL.md`).

## Exemption ledger — added 2026-08-20

67 pre-existing `validate` violations (dated 2026-06-09, predating this gate's strict wiring) have no honest fabrication-free fix: 49 point at evidence files bulk-deleted by commit `56f2417795c9` with the workable-items binary offering no "mark historically deleted, narrative stands" mechanism; 2 (same item, two history rows) name a malformed path that never existed under either its literal or `..`-normalized form; 16 have closure narratives naming a test class/module but no single unambiguous still-existing file (only bare module directories match — using one as "evidence" would be a guess). Two further violations (LVA-013 history ids 202/203) genuinely had two still-existing, unambiguous evidence files embedded in their own narrative and were FIXED via `correct-history-evidence`, not exempted.

Per the same §6.D Behavioral Coverage Contract precedent as `docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`: every exemption is a named, dated, individually-investigated row — never a blanket waiver. `LAVA_WORKABLE_ITEMS_EXEMPTIONS` overrides the ledger path for hermetic testing. Full investigation + per-category reasoning: `docs/superpowers/specs/2026-08-20-workable-items-evidence-exemptions.md`.

Falsifiability rehearsal (this session): removed the `LVA-009|16` line from the ledger → gate correctly FAILED with `CM-WORKABLE-ITEMS-SYNC: unexempted validate violation(s)`; restored the line → gate correctly PASSED again. `docs/workable_items.db` was independently confirmed unaffected by the rehearsal (`git diff` showed zero bytes changed on the DB across the mutate/restore cycle).

## Corpus-scope floor — added 2026-08-26

### The defect

Step 3 passes `--issues` and `--fixed` to `workable-items diff`, and those are the
**only two** markdown renderings it inspects. The tracker artifact is **four** files,
not two: the binary's `export` subcommand also writes `Issues_Summary.md` and
`Fixed_Summary.md` beside them. Those two were outside the gate's corpus entirely, so
they could carry arbitrary text while the gate still reported "DB ↔ Markdown in sync".

Measured, with a decisive control:

| Tree | Gate output | Exit |
|---|---|---|
| Real DB, real `Issues.md`/`Fixed.md`, **both `_Summary` files replaced** with `"TOTALS ARE FABRICATED: 9999 open items, none of which exist."` | `diff: DB and Markdown are in sync` | **0** |
| Same tree, one byte appended to `Issues.md` instead | `~ LVA-152 body differs ... diff: 1 difference(s)` | 1 |

Fabricating half the artifact passed; touching the other half failed. A gate that
certifies "in sync" having read half the artifact asserts nothing about the other half
— and the `_Summary` files are precisely the ones a reader skims for totals.

### Why the check is done here rather than in the binary

The canonical binary's `diff` subcommand takes no `_Summary` flags — `workable-items
diff --help` offers only `-db` / `-issues` / `-fixed`. Rather than reimplement the
summary format on the Lava side (which would drift from the renderer the moment either
changes), the gate **regenerates** the summaries from the same DB with
`export --no-formats` into a temp dir and compares bytes. Same source of truth, same
renderer, no second implementation to keep in lockstep.

### What the gate now refuses

Three refusals, all **exit 1**:

1. **A `_Summary` file is absent** — `summary tracker(s) ABSENT — the gate would certify
   a partial artifact.` The message states what was examined (`2 of the 4 files 'export'
   produces`) and distinguishes the cause: these are *output* files of the same renderer,
   so an absent one is a regeneration that never ran or was reverted, not an optional
   artifact. The remedy printed is the exact `export --no-formats` command.
2. **Regeneration failed** — `could not regenerate the summary trackers for comparison.`
   Explicitly labelled a **tooling failure, not a sync verdict**: skipping it would let
   the summary half go unchecked in silence. The binary's stderr is reproduced, indented.
3. **A `_Summary` file is stale** — `summary tracker(s) are STALE against the DB`, with
   the drifting paths named and the first differing lines of a unified diff shown so the
   drift is visible without a second command.

### Paths and overrides

`Issues_Summary.md` is resolved beside `--issues`, `Fixed_Summary.md` beside `--fixed`.
Both can be overridden for hermetic testing via `LAVA_WORKABLE_ITEMS_ISSUES_SUMMARY` and
`LAVA_WORKABLE_ITEMS_FIXED_SUMMARY`. The temp dir used for regeneration is removed on
exit via `trap`; the committed files are only ever **read**, never rewritten by the gate
— it reports drift, it does not silently repair it.

### §6.J falsifiability rehearsal of the floor itself

1. Overwrite `docs/Issues_Summary.md` with arbitrary text → run
   `bash scripts/check-workable-items.sh` → expect **exit 1**, `summary tracker(s) are
   STALE against the DB`, `docs/Issues_Summary.md` named, and the differing lines shown.
   Before this change: exit 0, `DB ↔ Markdown in sync`.
2. `rm docs/Fixed_Summary.md` → expect **exit 1**, `summary tracker(s) ABSENT`.
3. Regenerate with the printed `export --no-formats` command → expect exit 0 and the
   four-tracker success line.

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

- `0` — DB validated, **all four** rendered trackers (`Issues.md`, `Fixed.md`, `Issues_Summary.md`, `Fixed_Summary.md`) in sync with the DB, DB tracked. The success line names all four.
- `1` — validation failure, divergence in any of the four trackers, a missing `_Summary` file, a failed regeneration, build failure, or DB untracked.

## Hermetic test

`tests/check-workable-items/test_workable_items_sync.sh` — positive case (clean tree PASS), negative case (corrupt `docs/Issues.md` → gate FAILS), restore → PASS.

## Inheritance

Classification: project-specific (the LVA key + Lava's doc set are Lava-specific; the §11.4.93/95 mandates are universal per HelixConstitution).
