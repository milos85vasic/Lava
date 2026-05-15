# `scripts/bluff-hunt.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/bluff-hunt.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/bluff-hunt.sh — Seventh Law clause 5 (Recurring Bluff Hunt).

Picks 5 random *Test.kt files, prompts the operator to apply a deliberate
mutation to the production code each one claims to cover, runs the test,
and asserts the test fails. If a test still passes, it is a bluff —
the operator records it in the evidence file and either rewrites or
removes it.

Usage:
  ./scripts/bluff-hunt.sh                    # interactive
  ./scripts/bluff-hunt.sh --evidence <date>  # writes evidence file

This script is INTERACTIVE by design. The mutation is operator-driven;
the script frames the protocol but does not pretend to mutate the
codebase autonomously (which would itself be a class of bluff).
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/bluff-hunt.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
