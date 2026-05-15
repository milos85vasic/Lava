# `scripts/gen-script-doc.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/gen-script-doc.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/gen-script-doc.sh — one-time generator for docs/scripts/X.sh.md stubs.

Closes §6.AD-debt follow-up (task #61): backfill 19 missing companion
docs so CM-SCRIPT-DOCS-SYNC pre-push gate (Check 9) starts gating those
scripts too.

Usage:
  bash scripts/gen-script-doc.sh                          # all missing
  bash scripts/gen-script-doc.sh scripts/foo.sh           # only this one

Idempotent: skips files that already have a doc.

Inheritance: HelixConstitution §11.4.18 (script documentation mandate).
Classification: project-specific (the generator output shape is Lava-side).
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/gen-script-doc.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
