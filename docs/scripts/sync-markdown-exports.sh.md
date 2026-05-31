# sync-markdown-exports.sh

**Constitution:** §11.4.65 / CONST-066 — Universal Markdown Export Sync
**Closes:** §6.AF-debt (§11.4.65 item)

## Purpose

Generator + checker for the universal-markdown-export rule: every in-scope
committed markdown document MUST have synchronized `.html` and `.pdf` sibling
exports kept current with the markdown source.

- **HTML:** `pandoc "$md" --from gfm -o "$html" --standalone`
- **PDF:** `pandoc "$md" --from gfm -o "$pdf" --pdf-engine=weasyprint`

(`--from gfm` reads sources as GitHub-Flavored Markdown so a leading `---`-fenced
block is NOT misparsed as a YAML metadata block — the CHANGELOG.md failure class.)

The gate `scripts/check-markdown-export-sync.sh` (CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC)
wraps this script's `--check-only` mode.

## Scope

Authority: `docs/chaos-stress/EXPORT-AUDIT.md`.

**INCLUDED**
- Project-root `*.md` (depth 1)
- `docs/**/*.md`
- `scripts/**/*.md` (companion docs per §11.4.18)

**EXCLUDED** (each syncs its own / not source-export domain)
- `external/`, `prebuilts/`
- `submodules/**` (own-org + third-party — they sync their own)
- `constitution/**` (the HelixConstitution submodule syncs its own)
- `lava-api-go/**` source trees
- `build/`, `out/`, `.git/`, `node_modules/`
- `app/`, `core/`, `feature/` Kotlin source-code trees

## Usage

```bash
scripts/sync-markdown-exports.sh --check-only        # default; gate mode
scripts/sync-markdown-exports.sh --regenerate-all    # backfill/refresh all
scripts/sync-markdown-exports.sh --regenerate <file> # one .md's siblings
scripts/sync-markdown-exports.sh --help
```

## Modes

- **--check-only** — exit 1 if any in-scope `.md` lacks a sibling OR a sibling
  is older than its `.md` (mtime). This is the gate's mode.
- **--regenerate-all** — generate/refresh every in-scope sibling.
- **--regenerate <file>** — regenerate one specific `.md`'s siblings.

## Resource discipline (§6.T.2)

Each pandoc conversion is sub-second. Per-file `timeout 60` (GNU `timeout` or
`gtimeout`, when present) caps any runaway. The candidate set is capped at 500;
the script refuses to silently truncate (§6.J) and exits 2 if exceeded.

## Exit codes

- `0` — siblings present + current (check-only); all generated (regenerate)
- `1` — check-only found missing/stale siblings; or a generation failed
- `2` — tooling missing, scope/arg error, or candidate cap exceeded

## Generated artifacts are TRACKED

The produced `.html` / `.pdf` siblings ARE the synced artifacts mandated by
§11.4.65. They are committed (not gitignored). 224 in-scope `.md` files →
448 sibling files at backfill time.
