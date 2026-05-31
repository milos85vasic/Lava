# Migration to the canonical workable-items binary

**Revision:** 1
**Last modified:** 2026-05-31T00:00:00Z
**Description:** Plan to migrate Lava ticket tracking from the bespoke `tools/lava-tickets/` system (DB: `docs/tickets/tickets.db`) to the canonical `workable-items` Go binary shipped in the constitution submodule (`constitution/scripts/workable-items/`), per Constitution §11.4.93 + §11.4.74 catalogue-first. Tracked as LVA-4 ("LVA workable-items ticket system").

---

## 0. Executive summary (read first)

- **The LVA-key question is ANSWERED and requires NO upstream change.** The canonical `add` takes `--prefix` (default `WIT`; `crud.go` `addPrefixDefault = "WIT"`). `--prefix LVA` auto-allocates `LVA-NNN` (format `"%s-%03d"`, `crud.go` `nextID`). `--id LVA-N` stores `LVA-N` **verbatim** (no padding). **CONFIRMED at runtime:** `add --id LVA-7` → stored `LVA-7`; `add --prefix LVA` (no id) → stored `LVA-008` (continues from max). To preserve the existing `LVA-1`..`LVA-7` keys, pass `--id LVA-N` on each `add`. No constitution-submodule change is needed for LVA keys.
- **Build + run CONFIRMED.** Built from inside the module dir: `( cd constitution/scripts/workable-items && CGO_ENABLED=1 go build -o /tmp/wi-canon ./cmd/workable-items )` → **EXIT 0**, 7.4 MB binary. `--help` ran EXIT 0 (full output in §6). **`go build` from the repo ROOT FAILS** (`go: cannot find main module` — no root `go.mod`); you MUST `cd` into the module dir first. Self-contained `go.mod` (`go 1.21`, dep `mattn/go-sqlite3 v1.14.22`) + committed `go.sum`; schema is `//go:embed schema_embed.sql`. **`CGO_ENABLED=1` REQUIRED** (cgo sqlite driver; `CGO_ENABLED=0` fails).
- **End-to-end CONFIRMED** on a throwaway DB: `close LVA-7 --status fixed` moved it to Fixed with status `Fixed (→ Fixed.md)`; `validate` → "OK"; `sync db-to-md` + `diff` → "DB and Markdown are in sync" EXIT 0.
- **The 7 LVA items CAN be represented**, but the schemas differ substantially (§3). Hard parts: (a) LVA `priority` P0–P3 vs canonical free-text `severity`; (b) LVA Types `Incident`/`Debt`/`Chore` are NOT in the canonical closed set `{Bug,Feature,Task}` — and `add` does NOT reject them, it **silently coerces unknown types to `Task`** (CONFIRMED: `add Incident` and `add Debt` both stored `Task`; `parse.go normalizeType` `default: return "Task"`). So to store LVA-5 (Incident) as `Bug`, you MUST pass `add Bug …`, never `add Incident …`. (c) LVA's provenance columns (`source`, `source_ref`, `reopened_*`, `fix_commit_sha`, `validation_test`, `challenge_test`, `closure_log`) have no canonical column.

**§11.4.74 Catalogue-Check for the migration commit:**
`Catalogue-Check: reuse HelixDevelopment/HelixConstitution@<pin-sha> scripts/workable-items` — canonical binary covers the core lava-tickets functionality; bespoke system retired.

---

## 1. Build + invoke from the Lava root

```
constitution/scripts/workable-items/
├── go.mod / go.sum     module github.com/HelixDevelopment/HelixConstitution/scripts/workable-items; go 1.21; mattn/go-sqlite3 v1.14.22
├── schema.sql          authoritative DDL (items + item_history + obsolete_details + operator_block_details + firebase_metadata + doc_segments + meta)
└── cmd/workable-items/  main.go crud.go db.go parse.go sync.go report.go schema_embed.sql
```

**Build (resource-capped per §11.4.82 / §6.T.2) — MUST cd into the module dir:**

```bash
( cd constitution/scripts/workable-items && \
  CGO_ENABLED=1 GOMAXPROCS=2 nice -n 19 go build -o bin/workable-items ./cmd/workable-items )
```

The submodule's `.gitignore` ignores `bin/`, `*.db`, so a built binary is not committed into the submodule.

**Invoke (every subcommand requires `--db`; positional+flag order is free per `partitionArgs`):**

```bash
WI=constitution/scripts/workable-items/bin/workable-items
DB=docs/workable_items.db
$WI add <Bug|Feature|Task> <severity> --db $DB --id LVA-N --title "T" --description "D…"
$WI close LVA-N --db $DB --status <fixed|implemented|completed|obsolete> --evidence <path>
$WI sync db-to-md --db $DB --out-issues docs/Issues.md --out-fixed docs/Fixed.md
$WI sync md-to-db --db $DB --issues docs/Issues.md --fixed docs/Fixed.md
$WI diff     --db $DB --issues docs/Issues.md --fixed docs/Fixed.md   # exit 1 on divergence (CI gate)
$WI validate --db $DB
$WI report   --db $DB [--by-type|--by-status|--by-severity|--by-assigned|--by-creator|--obsolete-audit]
```

There is **no `update` and no `reopen` subcommand** — `add` always creates `Queued`; `close` is the only status-transition. Non-`Queued` open statuses need a DB-direct UPDATE (§4 Step 2c).

---

## 2. DB path + doc path

- **DB path = `docs/workable_items.db`** (§11.4.95 amends §11.4.93's dotfile to this tracked, non-dotfile path). Not hardcoded — pass `--db`; `openDB` `os.MkdirAll`s the parent dir.
- **§11.4.95 — the DB is TRACKED in git, NEVER gitignored.** `git add docs/workable_items.db`. The `.db-wal`/`.db-shm` sidecars MUST be gitignored; run `PRAGMA wal_checkpoint(TRUNCATE)` before staging (schema sets `journal_mode = WAL`).
- **Doc paths** are passed explicitly (no built-in default). Recommended: emit **`docs/Issues.md` + `docs/Fixed.md`** at the docs root (the §11.4.48 carve-out treats the canonical trackers as `docs/`-root constants). Lava keeps them under `docs/tickets/` today; migrating to `docs/` root aligns with the constitution. This plan assumes `docs/` root.

---

## 3. Schema comparison — the load-bearing differences

### Real lava-tickets schema (`docs/tickets/tickets.db`, confirmed via `.schema`)

```
tickets(seq INTEGER PK AUTOINCREMENT, id TEXT UNIQUE ('LVA-<seq>' via trigger),
  title, body, type→item_type, status→item_status, closure_status→closure_status,
  priority CHECK('P0'..'P3'),
  source CHECK('operator-report','crashlytics','self-discovered','bluff-hunt','code-review','user-report','automated-gate'),
  source_ref, reopened_{why,who,when,incident}, fix_commit_sha, validation_test,
  challenge_test, closure_log, operator_blocked_details, duplicate_of,
  created_at, updated_at, closed_at)
+ lookup tables item_type/item_status/closure_status/closure_status_for_type + triggers + views
```

**LVA closed sets (confirmed):**
- `item_status`: `Blocked | Closed | InProgress | InReview | Open | OperatorBlocked | Reopened`
- `item_type`: `Bug | Chore | Debt | Feature | Incident | Task`
- `closure_status`: `Closed | Completed | Duplicate | Fixed | Implemented | NotReproducible | Resolved | WontFix`

LVA `id` is `LVA-<seq>` (bare integer, NOT zero-padded).

### Canonical schema (`constitution/scripts/workable-items/schema.sql`)

```
items(atm_id, type CHECK('Bug','Feature','Task'), status CHECK(10-value closed set),
  severity, title, description, forensic_anchor, closure_criteria, composes_with,
  created_by, assigned_to, current_location CHECK('Issues','Fixed'), body_md,
  created_at, last_modified, PRIMARY KEY(atm_id, current_location))
+ item_history / obsolete_details / operator_block_details / firebase_metadata / doc_segments / meta
```

Canonical `status` closed set: `Queued | In progress | Ready for testing | In testing | Reopened | Operator-blocked | Fixed (→ Fixed.md) | Implemented (→ Fixed.md) | Completed (→ Fixed.md) | Obsolete (→ Fixed.md)`.

### Field mapping

| lava-tickets | canonical | maps cleanly? | note |
|---|---|---|---|
| `id` (`LVA-N`) | `items.atm_id` | YES — pass `--id LVA-N` (verbatim, CONFIRMED). Composite PK `(atm_id,current_location)` allows same id in both trackers; each item is in exactly one location, no conflict. |
| `seq` | — | DROPPED. No `seq` in canonical. Future auto-ids zero-pad (`LVA-008`) while legacy are bare (`LVA-7`); `atoiSafe` parses both so monotonicity holds. Cosmetic only. |
| `type` | `items.type` | **TRANSFORM REQUIRED.** Canonical only stores `{Bug,Feature,Task}` and `add` silently coerces anything else to `Task` (CONFIRMED). Map at the `add` command: `Incident → Bug` (pass `add Bug`), `Debt → Task`, `Chore → Task`. `Bug/Feature/Task` pass through. **LVA-2=Debt→Task, LVA-5=Incident→Bug.** |
| `status` | `items.status` | **TRANSFORM REQUIRED.** `Open→Queued`, `InProgress→In progress`, `OperatorBlocked→Operator-blocked`, `Closed→` type-aware terminal (§11.4.33), `Reopened→Reopened`, `Blocked/InReview→` nearest (`In progress`). |
| `closure_status` | folded into `items.status` | YES via §11.4.33 — terminal status word IS the closure status (`close --status fixed|implemented|completed|obsolete`). LVA's `Duplicate/NotReproducible/Resolved/WontFix` have NO canonical equivalent; map `Resolved→fixed`, `Duplicate→` close as the type-terminal + note duplicate_of in description, `WontFix/NotReproducible→obsolete`. None of the current 7 items use these. |
| `priority` (P0–P3) | `items.severity` | store the P-level literal as severity (free text). No information lost. |
| `title` | `items.title` | YES |
| `body` | `items.description` | YES — §11.4.91 floor (≥6 words OR ≥40 chars) enforced at `add`; real LVA bodies satisfy it. |
| (derived Issues/Fixed) | `items.current_location` | terminal status → `Fixed`, else `Issues`. |
| `source`,`source_ref` | — | no column. Fold `source_ref` into description as a `**Source:**` line so the audit link survives. |
| `reopened_*` | `item_history` (`Reopened`,`by`,`reason`,`evidence_path`) | none of the 7 is `Reopened` now → no port. |
| `fix_commit_sha`/`validation_test`/`challenge_test`/`closure_log` | `close --evidence` → `item_history.evidence_path` | `close` records ONE evidence path; use the richest (closure_log else validation_test). |
| `operator_blocked_details` | `operator_block_details` table | structurally yes, but no subcommand populates it — DB-direct INSERT (§4 Step 2c). LVA-5 is OperatorBlocked. |

### The 7 real LVA items (confirmed from `tickets.db`)

| id | LVA type | LVA status / closure | priority | → canonical `add` type | → canonical status | → location |
|---|---|---|---|---|---|---|
| LVA-1 | Bug | Closed / Fixed | P1 | Bug | `Fixed (→ Fixed.md)` | Fixed |
| LVA-2 | Debt | Closed / Closed | P1 | Task | `Completed (→ Fixed.md)` | Fixed |
| LVA-3 | Task | InProgress | P1 | Task | `In progress` | Issues |
| LVA-4 | Feature | InProgress | P1 | Feature | `In progress` | Issues |
| LVA-5 | Incident | OperatorBlocked | P0 | **Bug** (NOT Incident — would coerce to Task) | `Operator-blocked` | Issues |
| LVA-6 | Task | Open | P2 | Task | `Queued` | Issues |
| LVA-7 | Task | Open | P2 | Task | `Queued` | Issues |

Titles/bodies: quote verbatim from `sqlite3 docs/tickets/tickets.db "SELECT id,title,body FROM tickets ORDER BY seq"` — do NOT paraphrase (§11.4.6).

---

## 4. Step-by-step migration (main-agent-executable)

> CWD = repo root. `WI=constitution/scripts/workable-items/bin/workable-items`, `DB=docs/workable_items.db`.

### Step 1 — build + capture evidence

```bash
( cd constitution/scripts/workable-items && CGO_ENABLED=1 GOMAXPROCS=2 nice -n 19 go build -o bin/workable-items ./cmd/workable-items )
"$WI" --help    # capture as §11.4.5 evidence (EXIT 0 confirmed)
```

### Step 2 — seed 7 items, preserving exact keys

Pull `--title`/`--description` from the DB for each. Use the canonical `add` type per the §3 table (`Incident→Bug`, `Debt→Task`). Severity = the P-level.

```bash
rm -f "$DB" "$DB-wal" "$DB-shm"
"$WI" add Bug     P1 --db "$DB" --id LVA-1 --title "<LVA-1 title>" --description "<LVA-1 body>"
"$WI" add Task    P1 --db "$DB" --id LVA-2 --title "<LVA-2 title>" --description "<LVA-2 body>"
"$WI" add Task    P1 --db "$DB" --id LVA-3 --title "<LVA-3 title>" --description "<LVA-3 body>"
"$WI" add Feature P1 --db "$DB" --id LVA-4 --title "<LVA-4 title>" --description "<LVA-4 body>"
"$WI" add Bug     P0 --db "$DB" --id LVA-5 --title "<LVA-5 title>" --description "<LVA-5 body>"   # Incident→Bug
"$WI" add Task    P2 --db "$DB" --id LVA-6 --title "<LVA-6 title>" --description "<LVA-6 body>"
"$WI" add Task    P2 --db "$DB" --id LVA-7 --title "<LVA-7 title>" --description "<LVA-7 body>"
```

### Step 2b — close the two terminal items (LVA-1, LVA-2)

```bash
"$WI" close LVA-1 --db "$DB" --status fixed \
  --evidence feature/credentials/src/test/kotlin/lava/feature/credentials/CredentialsViewModelTest.kt
"$WI" close LVA-2 --db "$DB" --status completed \
  --evidence .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json
```

Both paths are the genuine evidence already in the LVA rows (honest, non-bluff).

### Step 2c — non-`Queued` open statuses (LVA-3, LVA-4 `In progress`; LVA-5 `Operator-blocked`)

`add` only produces `Queued`; no `update` subcommand. DB-direct (recommended now):

```bash
sqlite3 "$DB" "UPDATE items SET status='In progress',
  body_md=replace(body_md,'**Status:** Queued','**Status:** In progress')
  WHERE atm_id IN ('LVA-3','LVA-4');"
sqlite3 "$DB" "UPDATE items SET status='Operator-blocked',
  body_md=replace(body_md,'**Status:** Queued','**Status:** Operator-blocked')
  WHERE atm_id='LVA-5';"
sqlite3 "$DB" "INSERT INTO operator_block_details(atm_id,what,why_exhausted_alternatives,unblock_condition,who)
  VALUES('LVA-5','<what from tickets.db operator_blocked_details>','<why>','<unblock>','Operator');"
```

The `body_md replace()` keeps the §11.4.93 round-trip green. (Alternative: extend the canonical binary upstream with `update`/`block` subcommands per §11.4.74 — OWED, not blocking.)

### Step 3 — generate doc set + verify

```bash
sqlite3 "$DB" "PRAGMA wal_checkpoint(TRUNCATE);"
"$WI" sync db-to-md --db "$DB" --out-issues docs/Issues.md --out-fixed docs/Fixed.md
"$WI" validate --db "$DB"     # expect "OK — 7 items"
"$WI" diff --db "$DB" --issues docs/Issues.md --fixed docs/Fixed.md   # expect "in sync", exit 0
"$WI" report --db "$DB" --by-status
```

Binary generates **`Issues.md` + `Fixed.md` only**. Summaries (`Issues_Summary.md`/`Fixed_Summary.md`) + HTML/PDF (§11.4.12/§11.4.53/§11.4.65) are NOT produced by it — `tools/lava-tickets/export.go`/`render.go` produced them today. After retiring that, regenerate via the project export pipeline OR the §11.4.106 Docs Chain engine. Do NOT leave stale `docs/tickets/*_Summary.*` / `docs/tickets/export/*` behind.

### Step 4 — retire the bespoke system (main agent does `git rm`)

- `tools/lava-tickets/` (whole dir).
- `docs/tickets/tickets.db` → replaced by `docs/workable_items.db`.
- `docs/tickets/schema.sql` → replaced by the canonical embedded schema.
- `docs/tickets/{Issues,Fixed,Issues_Summary,Fixed_Summary}.md` + `docs/tickets/export/*` → replaced by `docs/Issues.md`/`docs/Fixed.md` + regenerated summaries/exports.
- Keep `docs/tickets/DESIGN.md` + `docs/tickets/BUILD-EVIDENCE.md` as forensic anchors (or move under `docs/tickets/retired/`). Any path used as `--evidence` MUST remain reachable.

**Replace the `CM-LVA-TICKETS-SYNC` gate.** `UNCONFIRMED:` its exact wiring location — the grep for `CM-LVA-TICKETS-SYNC`/`lava-tickets` in `scripts/check-constitution.sh` + `.githooks/pre-push` did not return output this session (session I/O degradation), so the main agent MUST grep to locate it before editing. Replace its body with:
- `workable-items validate --db docs/workable_items.db`
- `workable-items diff --db docs/workable_items.db --issues docs/Issues.md --fixed docs/Fixed.md` (exit 1 = §11.4.93 `CM-WORKABLE-ITEMS-MD-DB-IN-SYNC`)
- `git ls-files docs/workable_items.db` non-empty = §11.4.95 `CM-WORKABLE-ITEMS-DB-TRACKED`
- gate builds `bin/workable-items` if absent, or `go run ./cmd/workable-items` from the module dir.

### Step 5 — governance + gitignore + CONTINUATION (SAME commit, §6.S/§12.10)

- **`.gitignore`:** add `docs/workable_items.db-wal` + `docs/workable_items.db-shm`. Do NOT ignore `docs/workable_items.db` (§11.4.95). Remove dead `docs/tickets/` lines.
- **`CLAUDE.md` §6.AF:** rewrite to point at the canonical binary (`constitution/scripts/workable-items/`), DB `docs/workable_items.db`, docs `docs/Issues.md`/`docs/Fixed.md`, the LVA-prefix convention (`--id LVA-N` to preserve, `--prefix LVA` for new), the Incident→Bug / Debt→Task / Chore→Task type-mapping decision, and the new gate. Add `Catalogue-Check: reuse …` + `Classification: project-specific`.
- **`docs/CONTINUATION.md`:** record LVA-4 progress, new DB+doc paths, retired `tools/lava-tickets/`, OWED follow-ups (canonical `update`/`reopen`/`block` subcommand extension; summary+export pipeline standardisation; the Incident/Debt/Chore type-coercion-to-Task caveat; source/source_ref folded into description).

### Step 6 — Catalogue-Check + commit (main agent)

```
Catalogue-Check: reuse HelixDevelopment/HelixConstitution@<pin-sha> scripts/workable-items
Classification: project-specific (Lava consumes the canonical binary; LVA-prefix + Incident→Bug/Debt→Task/Chore→Task mapping is Lava-specific)
```

---

## 5. Main-agent-doable vs operator/upstream-blocked

| Item | Status |
|---|---|
| Build canonical binary | **DONE/feasible** (CONFIRMED EXIT 0 from module dir; root build fails — cd in first). |
| Seed 7 items with exact keys (`--id LVA-N`) | **Main-agent-doable** (CONFIRMED `--id` verbatim + `--prefix` auto-alloc). |
| Type transform (Incident→Bug, Debt/Chore→Task) | **Main-agent-doable** (mandatory: `add Incident`/`add Debt` silently coerce to Task — CONFIRMED — so pass the canonical type explicitly). |
| Status transform + close LVA-1/LVA-2 | **Main-agent-doable** (CONFIRMED close→Fixed + validate OK). |
| LVA-3/4 `In progress`, LVA-5 `Operator-blocked` + operator_block_details | **Main-agent-doable** via DB-direct UPDATE + `body_md` replace + INSERT (no `update`/`block` subcommand). |
| Generate Issues.md/Fixed.md + validate + diff | **Main-agent-doable** (CONFIRMED round-trip in sync). |
| Retire `tools/lava-tickets/` + replace gate + update CLAUDE/.gitignore/CONTINUATION | **Main-agent-doable.** Grep `CM-LVA-TICKETS-SYNC` first to locate gate wiring (`UNCONFIRMED:` location this session). |
| Summary docs + HTML/PDF exports | **Main-agent-doable** but depends on pandoc/weasyprint + project export pipeline (binary does NOT emit them). |
| `update`/`reopen`/`block` upstream extensions | **OWED upstream-extend** (§11.4.74), NOT blocking. Requires §11.4.26 7-step constitution-submodule pipeline + push to its 4 upstreams. File as a future LVA item. **`--prefix` default change is NOT needed.** |

---

## 6. Captured evidence (this session, from this checkout)

`go build` from inside `constitution/scripts/workable-items` with `CGO_ENABLED=1` → **EXIT 0**, 7.4 MB binary. `go build` from repo root → FAILS `go: cannot find main module`.

`--help` (EXIT 0, verbatim):

```
workable-items — §11.4.93 SQLite-SSoT for workable items

Usage:
  workable-items <subcommand> [args...]

Subcommands:
  sync md-to-db --db <p> [--issues <p>] [--fixed <p>]   Parse trackers, upsert DB.
  sync db-to-md --db <p> [--out-issues <p>] [--out-fixed <p>]  Regenerate trackers from DB.
  diff --db <p> [--issues <p>] [--fixed <p>]            Show DB vs Markdown divergence.
  validate --db <p>                                     Closed-set + §11.4.91 invariants.
  add <type> <severity> --db <p> --title <T> --description <D> [--id <id>] [--prefix <P>] [--created-by <h>] [--assigned-to <h>]
                                                        Create a new Queued item in Issues.
  close <atm-id> --db <p> --status <fixed|implemented|completed|obsolete> --evidence <p>
                                                        Atomic Issues→Fixed closure (§11.4.19).
  report --db <p> [--by-type|--by-status|--by-severity|--by-assigned|--by-creator|--obsolete-audit]
                                                        Read-only grouped tally / §11.4.90 audit.

Canonical authority: Constitution.md §11.4.93.
```

Runtime behaviour proofs (throwaway DB):
- `add Bug P0 --id LVA-7 …` → `add: created LVA-7 (Bug, status=Queued)`; stored atm_id = `LVA-7`.
- `add Task P2 --prefix LVA …` (no id) → `add: created LVA-008 (Task, status=Queued)`; stored atm_id = `LVA-008`.
- `add Incident …` → `(Task, …)` and `add Debt …` → `(Task, …)` — **unknown types silently coerced to Task**.
- `close LVA-7 --status fixed --evidence <p>` → `close: moved LVA-7 Issues→Fixed (status=Fixed (→ Fixed.md))`.
- `validate` → `validate: OK — 2 items, all invariants satisfied`.
- `sync db-to-md` + `diff` → `diff: DB and Markdown are in sync` (EXIT 0).

## 7. The LVA-key answer (definitive)

**Configurable — NO upstream change required.** Source (`crud.go`): `const addPrefixDefault = "WIT"`; `nextID` returns `fmt.Sprintf("%s-%03d", prefix, max+1)`; explicit `--id` stored as `strings.TrimSpace(*explicitID)` verbatim. Confirmed at runtime (§6). Preserve `LVA-1`..`LVA-7` via `--id LVA-N`; new tickets via `--prefix LVA` (zero-padded `LVA-NNN`). Neither path needs a constitution-submodule edit.
