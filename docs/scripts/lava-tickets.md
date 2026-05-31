# `lava-tickets` — LVA workable-items ticket system CLI

**Source:** `tools/lava-tickets/` (Go module `digital.vasic.lava.tickets`, pure
`modernc.org/sqlite`, no CGO).
**Constitutional basis:** HelixConstitution §11.4.93 (SQLite single-source-of-truth
for workable items) + §11.4.95 (DB tracked in git) + §11.4.106 (mechanical
doc↔DB sync, byte-identical round-trip) + §11.4.15/16/33/34 (item-tracking
covenant). Lava §6.A (real-binary contract test), §6.J (anti-bluff), §6.U (no
sudo), §6.T.2 (resource limits). **Key prefix: `LVA`** (operator directive,
§6.L 68th invocation, 2026-05-31; design at `docs/tickets/DESIGN.md`).

## What it is

The SQLite DB at `docs/tickets/tickets.db` is the single source of truth for the
project's workable items. The four markdown trackers
(`Issues.md` / `Fixed.md` / `Issues_Summary.md` / `Fixed_Summary.md`) are DERIVED
artifacts produced by `gen`. `verify` regenerates them in memory and asserts
byte-identity with what is on disk — the §11.4.106 round-trip guarantee.

## Build

```bash
cd tools/lava-tickets
GOFLAGS=-mod=mod GOMAXPROCS=2 go build -o bin/lava-tickets .
```

## Subcommands

| Subcommand | Purpose |
|-----------|---------|
| `init [--db PATH]` | Create the DB and apply `docs/tickets/schema.sql`. |
| `add --title T --type T [flags]` | Insert a ticket; prints the new `LVA-N` id. |
| `update --id LVA-N [field=value …]` | Update fields on a ticket. |
| `close --id LVA-N --closure-status S [..]` | Close a ticket; the §11.4.33 trigger enforces a type-valid closure verb. |
| `reopen --id LVA-N --why W --who O --when T --incident I` | Reopen with mandatory §11.4.34 attribution. |
| `gen [--db PATH] [--out DIR]` | Generate the 4 markdown trackers from the DB. |
| `verify [--db PATH] [--out DIR]` | Byte-identical round-trip check (§11.4.106). Exits non-zero on drift. |
| `import [--db PATH] [--in DIR]` | Reconcile the markdown trackers against the DB rows (inverse projection). |
| `export --format html\|pdf\|docx [--out DIR] [--export-dir DIR]` | Export. HTML is pure-Go (always works). PDF/DOCX run pandoc inside a podman/docker container; if no runtime/image is available the command prints an operator-actionable message and exits 3 — it NEVER fakes a file. |
| `list [--db PATH] [--status S]` | Print tickets (debug helper). |
| `version` | Print version. |

Defaults: `--db docs/tickets/tickets.db`, `--out docs/tickets`.

## Closed-set vocabularies (enforced by DB triggers/FKs)

- **Type** (§11.4.16): `Bug`, `Feature`, `Task` (+ Lava extras `Chore`, `Incident`, `Debt`).
- **Status** (§11.4.15/21): `Open`, `InProgress`, `Blocked`, `OperatorBlocked`, `InReview`, `Closed`, `Reopened`.
- **Closure status** (§11.4.33, type-aware): Bug→`Fixed`, Feature→`Implemented`, Task/Chore→`Completed`, Incident→`Resolved`, Debt→`Closed` (+ `WontFix`/`Duplicate`/`NotReproducible` where the type allows). The `closure_status_for_type` table + `trg_closure_status_typeaware` trigger reject invalid pairings.
- **Reopen attribution** (§11.4.34): `reopen` requires all of `--why --who --when --incident`; `trg_reopen_attribution` rejects a partial reopen.

## Typical workflow

```bash
lava-tickets init
lava-tickets add --title "Fix login crash" --type Bug --priority P1
# → LVA-8
lava-tickets close --id LVA-8 --closure-status Fixed --fix-commit <sha> \
  --validation-test path/to/Test.kt
lava-tickets gen          # regenerate the 4 trackers
lava-tickets verify       # MUST pass byte-identical (§11.4.106)
lava-tickets export --format html
```

## DB-in-git (§11.4.95)

`docs/tickets/tickets.db` is **TRACKED in git, never gitignored.** Before
committing, checkpoint the WAL so the file is self-contained:

```bash
sqlite3 docs/tickets/tickets.db "PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;"
```

The transient `*.db-wal` / `*.db-shm` sidecars, the build `bin/`, and scratch
`_*.sh` drivers are gitignored.

## Tests

```bash
cd tools/lava-tickets
GOFLAGS=-mod=mod GOMAXPROCS=2 go test ./...
```

The contract test (`tickets_test.go`) builds the real binary and asserts: the
subcommand surface (§6.A), byte-identical gen↔verify round-trip plus a
falsifiability rehearsal (corrupting a tracker makes verify FAIL), import
reconciliation, the §11.4.33 + §11.4.34 trigger contracts, the pure-Go HTML
exporter, and the §6.J no-fake-PDF guarantee.
