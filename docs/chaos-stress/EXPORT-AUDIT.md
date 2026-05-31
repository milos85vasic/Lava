# Chaos-Stress Docs — Markdown Export Sibling Audit (§11.4.65)

**Status:** Audit complete — recommendation: **§6.AF-debt** (do NOT generate a sibling pile this cycle)
**Constitution:** §11.4.65 (CONST-066 universal-markdown-export) · §11.4.61/64 (metadata table + ToC) · §6.U (no sudo) · §6.T.2 (resource limits) · §11.4.6 (no-guessing)
**Last updated:** 2026-05-31
**Owner:** docs-export audit subagent (read-only git; no commit/push)
**Scope:** the NEW markdown docs added in the last ~10 commits OUTSIDE `docs/tickets/`, `docs/CODEGRAPH.md`, `.codegraph/`, `constitution/`

---

## 1. Export-tooling reality

| Probe | Result (CONFIRMED) |
| --- | --- |
| `command -v pandoc` | **PRESENT** — `/opt/homebrew/bin/pandoc` (pandoc 3.9.0.2) |
| `command -v weasyprint` | **PRESENT** — `/opt/homebrew/bin/weasyprint` (WeasyPrint 66.0) |
| `scripts/testing/sync_all_markdown_exports.sh` | **ABSENT** — no canonical Lava export script |
| Any `scripts/*export*` / `scripts/*markdown*sync*` | **NONE** (full-tree `find`) |
| Lava tracked files referencing `weasyprint`/`pandoc` | `scripts/build-stats-report.sh` only — that is the §11.4.24 **build-stats report renderer**, NOT a doc-sibling export tool |
| Constitution submodule (`constitution/`, pin `883ccc1`) | **HAS §11.4.65** (Constitution.md L100 ToC entry + §11.4.65 body) AND ships its own export triples: every governance doc (`Constitution`, `CLAUDE`, `AGENTS`, `QWEN`, `README`, `submodules-catalogue`) exists as `.md` + `.html` + `.pdf` + `.docx` at the submodule root |
| Constitution committed export *script* | `constitution/scripts/` contains only `codegraph_sync.sh`, `codegraph_update.sh`, `workable-items/` — **no committed markdown-export script** is visible in the pinned tree; the constitution's `.html`/`.pdf`/`.docx` triples appear to be produced by an upstream-internal tool not vendored into the submodule |

**Conclusion:**
- The **toolchain to generate siblings exists on this host** (pandoc + weasyprint) — generate-now is *physically feasible*, contrary to a no-tooling blocker.
- But **Lava itself has no committed Markdown→HTML/PDF export mechanism**: no `sync_all_markdown_exports.sh`, no delegation wrapper to the constitution submodule, and the only weasyprint consumer is the build-stats renderer (a different §11.4.24 concern).
- The constitution submodule **demonstrates the §11.4.65 convention** (it keeps its own doc triples in sync) but does **not export a reusable script** into the pinned tree that Lava could invoke.

## 2. Is §11.4.65 ENFORCED in Lava today?

**NO.** Evidence:
- `scripts/verify-all-constitution-rules.sh` — no `CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC` gate; no reference to `markdown`, `export`, `sibling`, `.html`, `.pdf`, or `11.4.65`.
- `scripts/check-constitution.sh` — no markdown-export gate.
- `CLAUDE.md` — `§6.AF` exists but covers **HelixConstitution §11.4.79–106 adoption** (constitution pin bump + LVA tickets + chaos-stress); it does **not** wire §11.4.65 universal-markdown-export. §6.AF-debt enumerates owed items but markdown-export-sync is not among them.

§11.4.65 is inherited (binding by transitive reference per §6.AD.8) but **un-wired as a project-side gate** — the same posture as the other un-implemented `CM-*` gates tracked under §6.AD-debt.

## 3. Per-doc sibling status (in-scope NEW docs)

| Doc (repo-relative) | `.md` | `.html` | `.pdf` | §11.4.61 metadata | ToC |
| --- | --- | --- | --- | --- | --- |
| `docs/chaos-stress/DESIGN.md` | Y (14353B) | **N** | **N** | bold key:value header block (not pipe-table) | numbered `##` sections, no explicit ToC list |
| `docs/chaos-stress/EVIDENCE-phase1.md` | Y (4548B) | **N** | **N** | partial | none |
| `docs/scripts/run-chaos-stress.sh.md` | Y (2202B) | **N** | **N** | script-companion free-form | n/a (short) |
| `docs/scripts/check-lva-tickets.sh.md` | Y (4661B) | **N** | **N** | script-companion free-form | n/a (short) |
| `lava-api-go/tests/NEW-TESTS-EVIDENCE.md` | Y (9250B) | **N** | **N** | evidence free-form | none |

**Correction to the task's premise:** the task said the 3 ad-hoc PDFs / commit `85a4a7f5` exist — **they do not**. There are **zero** `.html` and **zero** `.pdf` siblings for any in-scope doc. (`git show 85a4a7f5` returned no such commit; the only `*.pdf`/`*.html` doc siblings in the tree are under `docs/tickets/export/` — another stream's domain, out of scope — and the constitution submodule root.) `lava-api-go/tests/NEW-TESTS-EVIDENCE.md` DOES exist (9250B), contrary to the task's "may not exist" note.

**Other NEW `.md` in the last 10 commits, outside excluded paths (no siblings either, noted for completeness, NOT in this task's generate-scope):** `docs/scripts/lava-tickets.md`, `docs/scripts/verify-codegraph.sh.md`, `docs/superpowers/specs/2026-05-20-codegraph-incorporation-design.md`, `QWEN.md`, `lava-api-go/QWEN.md`, `lava-api-go/tests/stress/README.md`, `tests/check-lva-tickets/EVIDENCE.md`, `.lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md`.

## 4. Recommendation — §6.AF-debt (NOT generate-now)

**Record §11.4.65 universal-markdown-export enforcement as §6.AF-debt; do NOT hand-generate siblings for these docs this cycle.** The toolchain exists, so this is a *policy* recommendation, not a *capability* blocker:

1. **No convention to be consistent with.** Lava has no export script and no §11.4.65 gate. Hand-running `pandoc` on 5 docs now creates artifacts with **nothing to keep them in sync** — the next `.md` edit silently desyncs the `.html`/`.pdf`, which is exactly the green-looking-but-stale bluff class the Anti-Bluff Pact (§6.J/§6.L) exists to prevent. A maintained sibling needs a re-sync gate; an unmaintained one is worse than none.
2. **Partial coverage is misleading.** If §11.4.65 binds, it binds for **all** non-source docs (the §3 list is ~13 files, plus the broader tree). Generating siblings for just the 5 chaos-stress docs implies a convention that the other 8+ NEW docs (and the entire pre-existing `docs/` tree) violate — making the repo *look* partially-compliant while being uniformly non-enforced.
3. **Metadata/ToC is non-conformant.** None of the 5 docs carry a §11.4.61 pipe-table metadata block or explicit ToC. Generating HTML/PDF from non-conformant source bakes the non-conformance into the rendered artifacts; the right fix normalizes source first, then renders once.
4. **The constitution shows the right pattern.** The submodule keeps `.md`+`.html`+`.pdf`+`.docx` in lockstep via a real (upstream-internal) tool. Lava should mirror that with a committed `scripts/testing/sync_all_markdown_exports.sh` (pandoc `--standalone` HTML + `--pdf-engine=weasyprint` PDF), wire a gate, then backfill **all** docs in one pass — a dedicated phase, not a 5-file side effect of the chaos-stress cycle.

### Suggested §6.AF-debt addendum for CLAUDE.md (for the main agent to consider)
> **§6.AF-debt addendum — §11.4.65 universal Markdown export sync (2026-05-31).** §11.4.65 (CONST-066) is inherited but un-wired in Lava: no `scripts/testing/sync_all_markdown_exports.sh`, no `CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC` gate in `scripts/verify-all-constitution-rules.sh`. pandoc 3.9 + weasyprint 66 ARE installed on the operator host; the constitution submodule keeps its own `.md/.html/.pdf/.docx` triples in sync as the reference pattern. No in-scope `docs/` doc currently has `.html`/`.pdf` siblings (the `docs/tickets/export/` triples belong to the tickets stream). Closure owes: (1) a canonical Lava export script; (2) verify-all gate wiring; (3) one consistent sibling backfill across all non-source docs incl. §11.4.61 pipe-table metadata + ToC normalization. Until close, markdown-export sync is operator-and-reviewer-verified manually.

## 5. Files for git add (this cycle)

**Only this audit file** — no generated siblings (recommend §6.AF-debt over hand-generation):
- `docs/chaos-stress/EXPORT-AUDIT.md`
