# `scripts/tag.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/tag.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```

tag.sh — Lava release-tagging tool.

Tags each app/service with `Lava-<App>-<versionName>-<versionCode>`,
pushes every tag to all configured upstream remotes, then bumps the
corresponding versionName/versionCode in source files and pushes the
bump commit.

See docs/TAGGING.md for the full operator guide.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/tag.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
