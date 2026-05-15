# `scripts/check-fixture-freshness.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/check-fixture-freshness.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/check-fixture-freshness.sh — block stale tracker fixtures.

Per the SP-3a plan Task 5.18 + the developer guide §4 testing
requirements. Fixtures named with a YYYY-MM-DD date in the filename
are considered "fresh" if the date is <30 days old (no warning),
"stale" if 30-60 days old (warn), and "expired" if >60 days old
(block — exit non-zero).

Sixth Law clause 1 ("same surfaces the user touches") implies that
parsers must be tested against HTML that resembles what the user
actually sees today. Trackers change their HTML structure; an old
fixture is a green-test-against-stale-shape bluff.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/check-fixture-freshness.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
