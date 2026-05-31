# check-markdown-export-sync.sh

**Gate:** CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC
**Constitution:** §11.4.65 / CONST-066 — Universal Markdown Export Sync

## Purpose

Thin gate wrapper around `scripts/sync-markdown-exports.sh --check-only`.
Verifies every in-scope markdown document has synchronized, current `.html` +
`.pdf` siblings. Part of the `verify-all-constitution-rules.sh` sweep.

## Usage

```bash
scripts/check-markdown-export-sync.sh                              # advisory (default)
LAVA_MARKDOWN_EXPORT_STRICT=strict scripts/check-markdown-export-sync.sh  # strict
```

## Modes

- **advisory** (default): reports problems but exits 0 (non-blocking).
- **strict**: exits 1 on any missing/stale sibling. Flipped via
  `LAVA_MARKDOWN_EXPORT_STRICT=strict|1|true`.

## Exit codes

- `0` — all siblings present and current (or advisory mode with failures)
- `1` — strict mode + missing/stale siblings, or generator missing

## Fixing failures

```bash
scripts/sync-markdown-exports.sh --regenerate-all
```

## Wiring

Invoked by `scripts/verify-all-constitution-rules.sh` as gate
`CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC`. Wired **strict** because the backfill left
the tree fully synced (0 problems across 224 in-scope files).
