# `scripts/ci.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/ci.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/ci.sh — local-only CI gate for Lava.

Per the Local-Only CI/CD constitutional rule, this script IS the
project's CI/CD apparatus. The same script runs in three modes:

  --changed-only   Fast subset for the pre-push hook (Spotless,
                   unit tests of changed modules, constitutional
                   doc parser, forbidden-files check). No
                   real-device tests; no mutation tests.

  --full           All gates — unit tests across every module,
                   parity gate, mutation tests where wired,
                   fixture freshness, Compose UI Challenge Tests
                   (requires a connected Android device or
                   emulator). Used at tag time.

  (default)        Same as --full.

Per Sixth Law clause 5: passing CI is necessary, NOT sufficient for
a release. The operator real-device verification per Task 5.22 of
SP-3a is the load-bearing acceptance gate; this script certifies the
codebase is shippable, not that the user-visible feature is shipped.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/ci.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
