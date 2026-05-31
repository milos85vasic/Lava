# Evidence — CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC (§11.4.65 / CONST-066)

Closes §6.AF-debt §11.4.65 item (recommended by `docs/chaos-stress/EXPORT-AUDIT.md`).

All output below is verbatim captured from real runs on darwin/arm64.

## 1. Tooling check (CONFIRMED)

```
$ command -v pandoc; command -v weasyprint
/opt/homebrew/bin/pandoc
/opt/homebrew/bin/weasyprint
---exit:0---
```

Both present (matches `docs/chaos-stress/EXPORT-AUDIT.md`: pandoc 3.9.0.2,
WeasyPrint 66.0). HTML: `pandoc <md> --from gfm -o <html> --standalone`. PDF:
`pandoc <md> --from gfm -o <pdf> --pdf-engine=weasyprint`.

## 2. CHANGELOG.md conversion bug — found + fixed (anti-bluff)

The first `--regenerate-all` reported `125 ok, 1 failed (of 126)`. Captured
stderr root-caused it (CONFIRMED, not transient — deterministic every run):

```
Error parsing YAML metadata at "CHANGELOG.md" (line 419, column 1):
YAML parse exception at line 3, column 1, while scanning an alias:
did not find expected alphabetic or numeric character
[markdown-export] ERROR: HTML generation failed/timed-out for CHANGELOG.md
```

pandoc's default `markdown` reader interpreted a leading `---`-fenced block as a
YAML metadata block and choked on an `&`-alias-like token. **Fix:** the
generator now reads every source as `--from gfm` (GitHub-Flavored Markdown does
not parse YAML metadata blocks). This eliminates the whole failure class.

## 3. Backfill after fix — `--regenerate-all` (CONFIRMED, clean)

```
$ ./scripts/sync-markdown-exports.sh --regenerate-all
  ...
  generated: scripts/README-firebase.html + scripts/README-firebase.pdf
  [markdown-export] regenerate-all: 126 ok, 0 failed (of 126).
  [markdown-export] sibling files written: 252 (.html + .pdf per source).
```

**126 in-scope `.md` → 252 sibling files written (126 source files × 2),
0 failures.** Non-fatal weasyprint CSS warnings observed (e.g. `WARNING:
Ignored 'user-select: none' at 191:32, unknown property`) — siblings still
generate successfully; warnings come from pandoc's standalone HTML template CSS.

## 4. Gate `--check-only` PASS (CONFIRMED, strict)

```
$ ./scripts/sync-markdown-exports.sh --check-only
  [markdown-export] checked 126 in-scope .md file(s); 0 problem(s).

$ LAVA_MARKDOWN_EXPORT_STRICT=strict ./scripts/check-markdown-export-sync.sh
  [markdown-export] checked 126 in-scope .md file(s); 0 problem(s).
  [markdown-export] OK: all in-scope markdown siblings present and current
  rc=0
```

Tree fully synced (0 problems) → gate wired **STRICT** in
`scripts/verify-all-constitution-rules.sh`.

## 5. Hermetic falsifiability test (CONFIRMED)

`tests/check-markdown-export-sync/test_markdown_export_sync.sh` runs the REAL
generator + gate against a self-contained temp-repo fixture (no live-tree
mutation). Verbatim output:

```
test_markdown_export_sync.sh — falsifiability rehearsal
=======================================================
  PASS: strict gate PASSES when all siblings synced (expected=0 got=0)
  PASS: out-of-scope file excluded from generation (expected=yes got=yes)
  PASS: strict gate FAILS on deleted sibling (expected=1 got=1)
  PASS: strict gate PASSES after restore (expected=0 got=0)
  PASS: strict gate FAILS on backdated (stale) sibling (expected=1 got=1)
  PASS: strict gate PASSES after regeneration (expected=0 got=0)
  PASS: advisory mode exits 0 despite missing sibling (expected=0 got=0)

Results: PASS=7 FAIL=0
  rc:0
```

Falsifiability proven: deleting a sibling → gate FAILS; backdating a sibling
mtime below its `.md` → gate FAILS; restoring/regenerating → gate PASSES;
out-of-scope files get no siblings; advisory mode never blocks.

## Scope (authority: `docs/chaos-stress/EXPORT-AUDIT.md`)

- **INCLUDED:** project-root `*.md`, `docs/**/*.md`, `scripts/**/*.md`
- **EXCLUDED:** `external/`, `prebuilts/`, `submodules/**`, `constitution/**`,
  `lava-api-go/**`, `build/`, `out/`, `.git/`, `node_modules/`, `app/`, `core/`,
  `feature/` source trees.

## Files created

- `scripts/sync-markdown-exports.sh` (generator + checker, `--from gfm`)
- `scripts/check-markdown-export-sync.sh` (gate wrapper, advisory/strict)
- `docs/scripts/sync-markdown-exports.sh.md` (§11.4.18 companion)
- `docs/scripts/check-markdown-export-sync.sh.md` (§11.4.18 companion)
- `tests/check-markdown-export-sync/test_markdown_export_sync.sh` (hermetic test)
- `tests/check-markdown-export-sync/EVIDENCE.md` (this file)
- 252 generated `.html` / `.pdf` siblings (TRACKED per §11.4.65 — NOT gitignored)

Gate wired into `scripts/verify-all-constitution-rules.sh` as
`CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC` (strict) and recorded in
`docs/helix-constitution-gates.md`.
