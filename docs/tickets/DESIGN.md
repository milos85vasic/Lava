# LVA Ticket System — Design

**Status:** DESIGN (scaffold only; no DB binary, no generator scripts built yet)
**Key prefix:** `LVA` (operator directive, §6.L 68th invocation, 2026-05-31)
**Author:** design+scaffold subagent
**Constitutional basis:** HelixConstitution §11.4.15 / §11.4.16 / §11.4.19 / §11.4.21 / §11.4.33 / §11.4.34 / §11.4.54 / §11.4.55 / §11.4.65; Lava §6.AD.3, §6.O, §6.T

---

## 0. The key decision

The operator (§6.L 68th invocation, 2026-05-31) directed: define the ticket key
for the main workable-items SQLite database and related documents (Issues, Fixed,
Issues_Summary, Fixed_Summary) and their exports to PDF / HTML / DOCX. **The key
prefix is `LVA`.** Tickets are `LVA-1`, `LVA-2`, … — a stable, monotonic,
project-prefixed key. This is the Lava-specific instantiation of the
HelixConstitution §11.4.54 ATM-NNN ticket-identifier mandate (ATM → LVA).

The `seq INTEGER PRIMARY KEY AUTOINCREMENT` column is the monotonic integer; the
`id` column renders `'LVA-' || seq` via an `AFTER INSERT` trigger. AUTOINCREMENT
(not plain rowid) guarantees keys are never reused even after deletes — satisfying
§11.4.54's "monotonic, never renumbered, append-only".

## 1. What exists today vs. what is missing

| Thing | State (CONFIRMED via filesystem + sqlite3 + reading) |
|-------|------------------------------------------------------|
| `tickets.db` / `Issues.md` / `Fixed.md` / `Issues_Summary.md` / `Fixed_Summary.md` | **MISSING** — none exist in the Lava repo (`find` returned no matches outside the HelixQA submodule + `.claude/worktrees/`) |
| `docs/tickets/` | **CREATED by this scaffold** (`schema.sql` + this `DESIGN.md`) |
| `lava-api-go/internal/qa/ticket/*.go` | **IMPLEMENTED + REAL** — `generator.go` (398 lines) + `generator_test.go` (426 lines). Adapter over HelixQA `pkg/ticket.Generator` that emits §6.O Crashlytics CLOSURE LOGS (one md per closed Crashlytics issue). It is NOT a tickets DB: no SQLite, no Issues/Fixed tracker, no LVA key. Different concern; see §6. |
| HelixQA submodule `pkg/ticket` (15 Go files) | **ON DISK + REAL** at `submodules/helixqa/pkg/ticket/` (`ticket.go` 13.7 KB, `enhanced_generator.go`, `rich_capture.go`, OCU mapping/replay + tests). Emits `HQA-####` generic QA-session tickets. NOT keyed `LVA`, NOT SQLite. The Lava adapter wraps this. |
| `constitution/` submodule | **FULLY CHECKED OUT** — `Constitution.md` (7302 lines), `CLAUDE.md` (2485 lines) + pre-rendered `.html`/`.pdf`/`.docx` for each. Full §11.4.x text read for §2. |
| Equivalence-mapped state (§6.AD.3 Path B) | **EXISTS** — `docs/CONTINUATION.md` (§6.S) + `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md` (§6.O) + `.lava-ci-evidence/sixth-law-incidents/<date>-<slug>.json` |

**Conclusion:** no SQLite ticket DB nor Issues/Fixed/Summary tracker docs exist
keyed `LVA`. The two existing Go `ticket` packages are a DIFFERENT artifact —
Crashlytics/QA closure-log generators, not a workable-items database. This is a
greenfield build keyed `LVA` that complements them.

## 2. HelixConstitution mandates this design satisfies

Quoted from the on-disk `constitution/Constitution.md` (7302 lines) and
`constitution/CLAUDE.md` (2485 lines), both fully checked out and read:

- **§11.4.15 — Item-status tracking** (CLAUDE.md): *"Every active item in the
  project Issues file carries a `**Status:**` line… Six-state vocabulary:
  `Queued`, `In progress`, `Ready for testing`, `In testing`, `Reopened`,
  `Fixed (→ Fixed.md)`."* §11.4.21 adds a 7th value `Operator-blocked`. →
  `tickets.status` FK to `item_status`. NOTE: the constitution's literal
  vocabulary differs from this design's normalized lifecycle states — the
  generator MUST render constitution-literal Status strings in the produced
  `Issues.md` (mapping in §4); the DB stores normalized states for query sanity.
- **§11.4.16 — Item-type tracking** (CLAUDE.md): *"Three-value CLOSED
  vocabulary: `Bug`… `Feature`… `Task`…"* → `tickets.type` FK to `item_type`.
  This design adds Chore/Incident/Debt as Lava-domain extras; the generator MUST
  collapse them to the constitution 3-value closed set in rendered docs
  (Chore→Task, Incident→Bug, Debt→Task) OR the operator ratifies an extension.
  CONFIRMED constitution closed set = {Bug, Feature, Task}.
- **§11.4.19 — Fixed-document column-alignment** (Constitution.md line 1150+) +
  **§11.4.53 — Fixed_Summary parity**: Fixed.md + Fixed_Summary.md MUST carry
  Status + Type columns aligned with Issues_Summary, and
  `mtime(Fixed_Summary) ≥ mtime(Fixed)`. → `v_fixed` / `v_fixed_summary` VIEWS
  (in sync by construction); the generator emits all four `.md` + §11.4.65
  `.html` + `.pdf` siblings.
- **§11.4.21 / §11.4.54 — stable project-prefixed key.** §11.4.54 (ATM-NNN
  mandate): *"Every workable item… MUST carry a stable, unique, auto-incremental
  ATM-NNN ticket identifier. …monotonic, never renumbered, append-only."* For
  Lava the prefix is **LVA**. → the `LVA-<seq>` id with AUTOINCREMENT. §11.4.54
  also requires the LVA-ID as leftmost column in both summaries.
- **§11.4.33 — Type-aware closure-status vocabulary** (CLAUDE.md): closed map
  `Bug → Fixed (→ Fixed.md)`, `Feature → Implemented (→ Fixed.md)`,
  `Task → Completed (→ Fixed.md)`. → `closure_status` column +
  `closure_status_for_type` mapping + `trg_closure_status_typeaware` trigger
  (VALIDATED: Bug→Implemented rejected; Bug→Fixed accepted). The generator MUST
  emit the literal `(→ Fixed.md)` suffix.
- **§11.4.34 — Reopened-source attribution** (CLAUDE.md): every `Reopened`
  heading carries `**Reopened-Details:**` with **By** (AI|User), **On** (ISO),
  **Reason** (closed vocab `{test-failed | manual-testing-detected |
  captured-evidence-contradicts | end-user-report | cycle-re-discovered |
  design-reconsidered}`), **Evidence** (path). → `reopened_who` (By),
  `reopened_when` (On), `reopened_why` (Reason), `reopened_incident` (Evidence)
  columns + `trg_reopen_attribution` trigger (VALIDATED: reopen missing any of
  the four rejected). §11.4.55 adds per-item `docs/issues/<LVA-NNN>/Reopens.md`
  when reopens_count > 0 — see §7 (a `ticket_events` history table is the
  recommended follow-up).

Also honored: **§11.4.21 operator-blocked-details** → `operator_blocked_details`
+ trigger; **§11.4.65 / §11.4.60** universal Markdown export (`.md` + `.html` +
`.pdf` always in sync) → drives the export pipeline in §5.

**Validation evidence:** the schema was applied to a throwaway db with the
host `sqlite3` and every trigger was exercised — Bug→Implemented rejected
(`§11.4.33: Closed ticket needs a type-valid closure_status`), Bug→Fixed
accepted, reopen-without-attribution rejected (`§11.4.34: Reopen needs
reopened_why/who/when/incident`), reopen-with-attribution accepted, `LVA-1` /
`LVA-2` auto-rendered, `closed_at` auto-stamped, summary views populated. (No db
committed — it is a generated artifact.)

## 3. Schema

Full DDL in `docs/tickets/schema.sql`. Shape:

- `item_type`, `item_status`, `closure_status` — closed-set lookup tables
  (FK-enforced; auditable + extensible without code edits).
- `closure_status_for_type` — the §11.4.33 (type → valid closure verbs) mapping.
- `tickets` — the core table (seq/id, title, body, type, status, closure_status,
  priority, source/source_ref, reopened_*, fix_commit_sha / validation_test /
  challenge_test / closure_log, operator_blocked_details, duplicate_of,
  timestamps).
- Triggers — id rendering, updated_at touch, type-aware closure guard, reopen
  attribution guard, operator-blocked guard, closed_at stamp.
- Views — `v_issues`, `v_fixed`, `v_issues_summary`, `v_fixed_summary`.

No sudo, no service, no network — host `sqlite3` only.

## 4. Generated document set + vocabulary mapping

Four canonical markdown docs, generated FROM the db (single source of truth =
the db; the `.md` files + their `.html`/`.pdf`/`.docx` are derived artifacts):

| Doc | Source view | Content |
|-----|-------------|---------|
| `Issues.md` | `v_issues` | Non-closed tickets, leftmost `LVA ID` column (§11.4.54), then Type, Status, Priority, Title, Source |
| `Fixed.md` | `v_fixed` | Closed tickets, `LVA ID`, Type, Closure Status (literal `(→ Fixed.md)` per §11.4.33), Title, Fix SHA, Validation Test, Challenge Test, Closure Log |
| `Issues_Summary.md` | `v_issues_summary` | Counts by (Type × Status); §11.4.54 LVA-ID column on the detail rows |
| `Fixed_Summary.md` | `v_fixed_summary` | Counts by (Type × Closure Status); §11.4.19 column-aligned with Issues_Summary |

**DB-normalized → constitution-literal mapping the generator MUST apply:**

| DB `status` | Rendered `**Status:**` |
|-------------|------------------------|
| Open | Queued |
| InProgress | In progress |
| InReview | Ready for testing / In testing |
| Reopened | Reopened (+ `**Reopened-Details:**` block per §11.4.34) |
| OperatorBlocked | Operator-blocked (+ `**Operator-Block-Details:**` per §11.4.21) |
| Closed | the type-aware closure verb (next table) |

| DB `type` + `closure_status` | Rendered closure `**Status:**` (§11.4.33) |
|------------------------------|-------------------------------------------|
| Bug + Fixed | `Fixed (→ Fixed.md)` |
| Feature + Implemented | `Implemented (→ Fixed.md)` |
| Task + Completed | `Completed (→ Fixed.md)` |

DB-extra types (Chore/Incident/Debt) collapse to {Task,Bug,Task} in rendered
docs until the operator ratifies extending the constitution's 3-value set.

## 5. Export pipeline (PDF / HTML / DOCX) — built around what is ACTUALLY installed

**Tool reality on this host (CONFIRMED — clean per-tool `which` + per-module
`python3 -c "import X"` probe; everything needed is present):**

| Tool | Result | Evidence |
|------|--------|----------|
| `sqlite3` | **PRESENT** | `…/Android/sdk/platform-tools/sqlite3` — schema + all triggers validated against it |
| `python3` | **PRESENT** | `/opt/homebrew/opt/python@3.9/libexec/bin/python3` |
| `pandoc` | **PRESENT** | `/opt/homebrew/bin/pandoc` — covers md→HTML AND md→DOCX |
| `weasyprint` | **PRESENT** (binary + py module 66.0) | `/opt/homebrew/bin/weasyprint` + `import weasyprint` OK — covers HTML→PDF |
| python `python-docx` (`docx`) | **PRESENT** (1.2.0) | `import docx` OK — programmatic DOCX fallback |
| python `jinja2` | **PRESENT** (3.1.6) | `import jinja2` OK — optional templating |
| `go` | **PRESENT** | `/opt/homebrew/bin/go` — the Lava `internal/qa/ticket` adapter builds here |
| `wkhtmltopdf` | MISSING | not needed — weasyprint covers PDF |
| `libreoffice` / `soffice` | MISSING | not needed — pandoc covers DOCX |
| python `markdown` | MISSING | not needed — pandoc parses md |

> **Earlier-probe correction (no-guessing, §11.4.6).** A first probe printed a
> confusing interleaving (a stray `Traceback` aligned next to a later module's
> `OK`). The clean per-line probe (each `import` on its own invocation) is
> authoritative: pandoc + weasyprint + python-docx are ALL installed. The
> constitution submodule's pre-rendered `.html`/`.pdf`/`.docx` artifacts on disk
> corroborate that this exact pandoc + weasyprint pipeline already runs in this
> repo for §11.4.65 exports.

**Implication: the FULL pipeline (md + HTML + PDF + DOCX) is buildable TODAY with
no installs and no sudo.** §11.4.65 / §11.4.60 (every tracked `.md` has synced
`.html` + `.pdf` siblings; operator also asked for DOCX) is satisfiable now.

**Export pipeline (single tier — all tools present):**

1. **md generation:** `sqlite3` query → Python (stdlib `sqlite3`; `jinja2`
   optional) → `Issues.md` / `Fixed.md` / `Issues_Summary.md` / `Fixed_Summary.md`.
2. **HTML:** `pandoc <doc>.md -o <doc>.html --standalone` (matches the existing
   repo pattern that produced `constitution/*.html`).
3. **PDF:** `pandoc <doc>.md -o <doc>.pdf --pdf-engine=weasyprint` (the §11.4.65
   documented "pandoc HTML + weasyprint PDF" combo).
4. **DOCX:** `pandoc <doc>.md -o <doc>.docx` (operator's explicit ask; pandoc
   native DOCX writer — no LibreOffice needed). `python-docx` is the
   fine-grained-styling fallback.

Mirror the constitution's `sync_all_markdown_exports.sh` `timeout 60`-per-file
guard for consistency (§6.T.2 host-budget — these are sub-second conversions on
a handful of small docs).

**Operator-install items (NOT via sudo): NONE required** for the core
md/HTML/PDF/DOCX pipeline — every tool is already present. Optional future
hardening: run pandoc+weasyprint inside the Containers submodule (§6.K / §6.U /
§11.4.76) for cross-workstation reproducibility — not a blocker.

## 6. Relationship to the existing Go `ticket` packages + the §6.AD.3 mapping

**The two existing `ticket` Go packages are a DIFFERENT artifact and are NOT
superseded by the LVA system:**

- `submodules/helixqa/pkg/ticket/` (15 real Go files, `ticket.go` 13.7 KB) —
  HelixQA's `Generator` producing `HQA-####` QA-session tickets (generic markdown
  for any QA failure: crash, step failure, ANR). Reusable, project-agnostic.
- `lava-api-go/internal/qa/ticket/generator.go` (398 real lines) — Lava's adapter
  wrapping the HelixQA Generator to emit §6.O Crashlytics **closure logs** at
  `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md`. One file per closed
  Crashlytics issue.

Neither manages an Issues/Fixed workable-items tracker, neither uses SQLite,
neither is keyed `LVA`. They are output generators for incident/QA artifacts. The
LVA system is the **workable-items database** (the operator's directive target).
They COMPLEMENT each other: a Crashlytics issue closed via the
`internal/qa/ticket` adapter SHOULD also become an `Incident`/`Bug` LVA ticket
(status Closed, `closure_log` pointing at the generated md). Per §11.4.74
(catalogue-first / extend-don't-reimplement): the LVA generator MAY reuse HelixQA
`pkg/ticket` rendering helpers where they fit rather than re-rolling markdown —
flagged in §7. The Go adapter is REAL and working — do NOT delete it.

### Relationship to the §6.AD.3 Path-B equivalence mapping

§6.AD.3 Path B (2026-05-15) formally mapped HelixConstitution §11.4.33 /
§11.4.34 to Lava's existing artifacts (`CONTINUATION.md` + the two
`.lava-ci-evidence/` ledgers) and declared that mapping binding "rather than
requiring a parallel Issues.md / Fixed.md tracker."

**This LVA system COMPLEMENTS rather than immediately supersedes that mapping:**

- The operator's 68th-invocation directive now explicitly calls for the SQLite DB
  + Issues/Fixed/Summary docs + PDF/HTML/DOCX exports — i.e. the very parallel
  tracker Path B previously deemed optional. The LVA system is the
  operator-directed materialization of those trackers.
- Until the LVA system is populated and wired into the gates, the Path-B
  equivalence remains the binding compliance surface (do NOT remove the
  `.lava-ci-evidence/` ledgers or `CONTINUATION.md` state). The LVA db should be
  SEEDED from the existing `.lava-ci-evidence/crashlytics-resolved/*` +
  `sixth-law-incidents/*` so the two surfaces stay reconciled.
- Once the LVA db is authoritative and the generators run in CI (`scripts/ci.sh`),
  §6.AD.3 should be amended to note the LVA system as primary tracker with the
  `.lava-ci-evidence/` ledgers as forensic backing (a CLAUDE.md edit for the main
  agent / operator — NOT done in this scaffold).

This supersede-vs-complement decision is project-specific
(`Classification: project-specific`) and must be ratified by the operator before
the Path-B mapping text is rewritten.

## 7. Action list for the main agent (prioritized)

1. **Commit this scaffold** (`docs/tickets/DESIGN.md` + `docs/tickets/schema.sql`).
   Per §6.S update `docs/CONTINUATION.md` in the same commit (new docs/tickets
   surface + LVA key decision). Add a `Classification:` line per §11.4.17.
2. **Build the generator** — `scripts/tickets-gen.py` (stdlib `sqlite3`; jinja2
   optional, present) reads the db and emits Issues.md / Fixed.md /
   Issues_Summary.md / Fixed_Summary.md, then shells to `pandoc` (HTML + DOCX) +
   `pandoc --pdf-engine=weasyprint` (PDF) per §5 — ALL tools confirmed present,
   no install. Add a `docs/scripts/tickets-gen.md` companion per §11.4.18. Cover
   with a hermetic test per §6.A. Apply the §4 normalized→constitution-literal
   vocab mapping so rendered docs satisfy §11.4.15/16/33.
3. **Seed from existing evidence** — import `.lava-ci-evidence/crashlytics-resolved/*`
   (→ Bug/Incident, Closed, closure_log set) and `sixth-law-incidents/*`
   (→ Incident) as LVA tickets, reconciling with the Path-B surface (§6).
4. **§11.4.74 catalogue-check before re-rolling markdown.** Evaluate
   reusing/extending the HelixQA `pkg/ticket` + Lava `internal/qa/ticket`
   rendering helpers; record `Catalogue-Check: reuse|extend|no-match`. Do NOT
   delete the Go adapter (different concern — closure logs, not the tracker DB).
5. **Decide canonical tracker-doc location.** §11.4.48 carve-out fixes the 5
   canonical tracker docs at `docs/` ROOT (`docs/Issues.md`, …,
   `docs/CONTINUATION.md`). If Lava adopts that convention, rendered tracker docs
   go at `docs/` root with the SQLite db + schema + generator under
   `docs/tickets/`. Operator decision via §11.4.66 AskUserQuestion.
6. **Gitignore decision for `tickets.db`.** Recommend gitignore the binary +
   commit `schema.sql` + a `seed.sql` of INSERTs (reproducible, reviewable),
   with a §11.4.77 `.gitignore-meta/` entry declaring `sqlite3 db < seed.sql` as
   the regeneration mechanism. (Existing precedent: `.codegraph/codegraph.db` is
   gitignored with `codegraph index` as its regen mechanism.)
7. **No operator install required** for md/HTML/PDF/DOCX — pandoc + weasyprint +
   python-docx + sqlite3 + python3 + go all present (evidence §5).
8. **Wire gates** — add a `scripts/check-constitution.sh` check that the LVA db's
   triggers exist + generated docs are in sync (regenerate + `git diff
   --exit-code`) + `.md`/`.html`/`.pdf` mtimes aligned (§11.4.60/65), closing the
   §6.AD-debt `CM-ITEM-*` gates with a real implementation instead of
   equivalence-mapping. Add a `Classification:` line + paired §1.1 mutation test.
9. **Follow-up: `ticket_events` history table** for full §11.4.55 per-item
   Reopens.md reasoning chains (the current `reopened_*` columns hold only the
   latest reopen; full history needs an append-only events table).

---

## 8. Build outcome (2026-05-31, §6.L 68th invocation)

The system is BUILT and the design's open questions are resolved. Verbatim build
evidence: `docs/tickets/BUILD-EVIDENCE.md`.

- **Implementation language: Go, not Python.** The §7.2 design sketched a
  `scripts/tickets-gen.py`; the actual build is a Go module at
  `tools/lava-tickets/` (pure `modernc.org/sqlite`, no CGO) per the operator's
  Go-only directive. It implements `init / add / update / close / reopen / gen /
  verify / import / export / list`.
- **DB-in-git decision (§7.6 superseded):** `docs/tickets/tickets.db` IS
  **TRACKED in git, NOT gitignored** — §11.4.95 ("Workable-items SQLite DB
  TRACKED in git, NEVER gitignored", a NEW clause in the 53-commit delta the
  68th-cycle review surfaced) is explicit and overrides §7.6's gitignore-the-
  binary recommendation. The DB is checkpointed to DELETE journal mode before
  commit so it is self-contained; only `*.db-wal`/`*.db-shm` sidecars + `bin/` +
  scratch `_*.sh` are gitignored.
- **Round-trip (§11.4.106):** `gen` then `verify` is byte-identical — PROVEN, and
  the contract test includes a falsifiability rehearsal (corrupting a tracker
  makes `verify` FAIL).
- **Triggers (§11.4.33/34):** exercised by the binary's contract test —
  Bug→Implemented rejected, Bug→Fixed accepted, partial reopen rejected.
- **Export:** HTML is pure-Go (always works — `Issues.html` committed as proof).
  PDF/DOCX run pandoc inside a podman/docker container; when no runtime is up the
  tool exits 3 with an operator-actionable message and writes NO fake file
  (§6.J). Host `pandoc` is the documented operator fallback (real DOCX produced
  this session).
- **Seeded items:** 7 REAL items (LVA-1…LVA-7) derived from `docs/CONTINUATION.md`
  + the 68th-cycle constitution review + the two 2026-05-20 sixth-law incidents.
  No invented commit SHA (recorded as `pending` per §11.4.6 no-guessing).
- **§6.AD.3 Path-B vs SQLite-DB reconciliation (LVA-3) remains operator-gated** —
  this build materializes the tracker the §6.AD.3 carve-out previously deemed
  optional; whether it SUPERSEDES or COMPLEMENTS the `.lava-ci-evidence/` ledgers
  is the main-agent/operator decision (DESIGN §6), not made in this build.

---

`Classification: project-specific` (the LVA key + Lava's doc set + the Path-B
relationship are Lava-specific; the underlying §11.4.x mandates are universal and
inherited from HelixConstitution).
