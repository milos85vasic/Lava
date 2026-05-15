# `scripts/build-stats-report.sh` — User Guide

**Last verified:** 2026-05-14 (1.2.23 cycle)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + §11.4.24 (build-resource stats tracking)

## Overview

Reads the build-stats registry at `.lava-ci-evidence/build-stats/registry.tsv` and emits a derived Markdown report at `docs/build-stats/Stats.md`. Companion to `scripts/build-stats-sample.sh`.

Per HelixConstitution §11.4.24 the registry is the canonical source of truth; the Markdown report is derived (and HTML/PDF exports are derived from the Markdown via the §11.4.22 lightweight commit path's pipeline — TODO when pandoc + wkhtmltopdf are wired). Top of the report surfaces ever-values (min/max/mean across all tracked builds); per-build entries sorted most-recent-first.

## Usage

```bash
bash scripts/build-stats-report.sh
```

Idempotent — re-runs produce the same report (modulo the "Last regenerated" timestamp).

## Inputs

None (reads `.lava-ci-evidence/build-stats/registry.tsv` from the repo root).

## Outputs

- `docs/build-stats/Stats.md` — overwritten each run (canonical Markdown)
- `docs/build-stats/Stats.html` — derived via `pandoc -f gfm -t html5 --standalone` if `pandoc` is in PATH; skipped silently otherwise
- `docs/build-stats/Stats.pdf` — derived via `wkhtmltopdf` (preferred) OR `weasyprint` (fallback), whichever is in PATH; both absent → skipped silently

The HTML and PDF derivations were added 2026-05-15 (1.2.23 closure-cycle, task #64) per HelixConstitution §11.4.12 (auto-generated docs sync — every regeneration of the source MUST regenerate every derived format).

## Side-effects

- Creates `docs/build-stats/` if missing
- Overwrites the report file

## Empty / sparse registry

- If `registry.tsv` does not exist: emits a "No builds tracked yet" report with a hint about `build-stats-sample.sh wrap`
- If `registry.tsv` exists but contains 0 data rows: emits a "0 build rows" report

## Sort order

Per-build rows are reverse-sorted (most-recent timestamp first) via portable awk indexing (BSD/GNU compatible — `tac` is GNU-only and avoided here).

## Cross-references

- `docs/scripts/build-stats-sample.sh.md` (the sampler that produces the registry)
- `docs/helix-constitution-gates.md` (the gate inventory; this script's existence helps close `CM-BUILD-RESOURCE-STATS-TRACKER`)
- HelixConstitution `Constitution.md` §11.4.24
