# `scripts/inject-helix-inheritance-block.sh` — User Guide

**Last verified:** 2026-05-14 (1.2.23 cycle)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + Lava §6.AD (HelixConstitution Inheritance) + §6.AD-debt item 1 (per-scope CLAUDE.md / AGENTS.md / CONSTITUTION.md propagation)

## Overview

Idempotent debt-closure tool that injects the standard `## INHERITED FROM constitution/<File>.md` pointer-block into per-scope CLAUDE.md / AGENTS.md / CONSTITUTION.md files across the Lava project. Used during the §6.AD HelixConstitution incorporation cycle (1.2.23, 2026-05-14) to mechanically propagate the inheritance pattern to 54 in-scope files in one pass.

## Prerequisites

- `git` ≥ 2.30 in PATH
- HelixConstitution submodule mounted at `./constitution/` (the script's pointer-block REFERS to `constitution/<File>.md`; the submodule must exist for the references to resolve)

## Usage

### Apply to all in-scope files (default)

```bash
scripts/inject-helix-inheritance-block.sh
```

Walks the canonical scope (16 × `submodules/*/{CLAUDE,AGENTS,CONSTITUTION}.md` + 3 × `lava-api-go/{CLAUDE.md,AGENTS.md,CONSTITUTION.md}` + `core/CLAUDE.md` + `app/CLAUDE.md` + `feature/CLAUDE.md` = 54 files), and for each:

1. If the file already carries a `## INHERITED FROM constitution/` heading → skip with `✓ already present`
2. Else find the first H1 heading (`# Title`), inject the pointer-block immediately after it, report `+ injected after line N`
3. If no H1 found → skip with `⚠ no H1 — manual review needed`

### Apply to a specific file

```bash
scripts/inject-helix-inheritance-block.sh path/to/some/CLAUDE.md
```

Only that file is processed. Useful when adding a new submodule or new per-scope doc.

### Apply to multiple specific files

```bash
scripts/inject-helix-inheritance-block.sh file1.md file2.md file3.md
```

## Inputs

| Argument | Required? | Description |
|---|---|---|
| `[files...]` | no | Specific files to process (default: walk canonical scope) |

## Outputs

- Per-file modification logged to stdout (`+ added` / `✓ already present` / `⚠ errored`)
- Summary line: `Summary: added=N skipped-already-present=M errored=K`
- Exit 0 if errored=0; exit 1 otherwise

## Side-effects

- Modifies in-place via `head/cat/tail` + `mv` (no in-place sed; portable across BSD + GNU)
- Idempotent: re-running produces no diff once all files carry the block
- Does NOT commit, does NOT push, does NOT update submodule pins

## File-type-aware target resolution

The pointer-block's target file matches the consuming file's basename:

| Consuming file | Pointer target |
|---|---|
| `*CLAUDE.md` | `constitution/CLAUDE.md` |
| `*AGENTS.md` | `constitution/AGENTS.md` |
| `*CONSTITUTION.md` | `constitution/Constitution.md` (note capitalization — HelixConstitution names it `Constitution.md`, not `CONSTITUTION.md`) |

## Edge cases

### File has no H1 heading

The script reports `⚠ no H1 heading — skip (manual review needed)` and continues. Common cause: the file starts with a YAML front-matter block. Fix: add an H1 heading, or manually inject the block.

### File has a comment block / banner before the H1

The injection happens after the H1, so any pre-H1 banner content (license headers, etc.) is preserved as-is.

### Re-running after manual edits to the block

The idempotency check is `grep -qE '^## INHERITED FROM constitution/'`. If the heading was preserved (even if its body was edited), the script will skip. To force re-injection, delete the heading line first.

## Internal behaviour

For each in-scope file:

1. Idempotency: `grep -qE '^## INHERITED FROM constitution/'` — if present, skip
2. H1 detection: `awk '/^# / {print NR; exit}'` — find the first H1 line number
3. Block composition: `pointer_block_for "$BASENAME"` writes the file-type-aware block to a tempfile
4. Injection: `head -n H1 file > tmp; cat block >> tmp; tail -n +(H1+1) file >> tmp; mv tmp file`

The `head/cat/tail/mv` approach is intentional — BSD awk rejects multi-line strings via `-v`, so pure-awk injection is not portable. The tempfile approach works on both BSD and GNU userlands.

## Related scripts

- `scripts/check-constitution.sh` — verifies the propagation is complete; suggests this script when missing
- `scripts/commit_all.sh` — used to commit the inject results; do NOT auto-commit from this script

## Cross-references

- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance) clause 4 — the propagation requirement
- Lava `CLAUDE.md` §6.AD-debt item 1 — debt closure context
- HelixConstitution `CLAUDE.md` "How inheritance works" — the prescribed pattern
