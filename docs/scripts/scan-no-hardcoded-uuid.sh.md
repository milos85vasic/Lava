# `scripts/scan-no-hardcoded-uuid.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/scan-no-hardcoded-uuid.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/scan-no-hardcoded-uuid.sh — standalone §6.R UUID scanner.

Purpose: enforce the §6.R No-Hardcoding Mandate clause that no 36-char
UUIDs appear in tracked source outside the exemption set. Extracted as
a standalone script so the hermetic test suite can invoke ONLY this
rule (without piggy-backing on the broader check-constitution.sh and
its silent-PASS fall-through bluff). The main checker delegates here;
tests/check-constitution/test_no_hardcoded_uuid.sh delegates here.

Exit codes:
  0 — no UUID violations
  1 — UUID violation(s) found (paths printed to stderr)

Exemptions (kept in lockstep with the §6.R clause body):
  .env.example                                — placeholder file
  .lava-ci-evidence/sixth-law-incidents/      — forensic anchors
  docs/superpowers/specs/*.md                 — design docs
  docs/superpowers/plans/*.md                 — implementation plans
  *_test.go, *Test.kt, *Tests.kt, *Test.java  — synthetic test fixtures
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/scan-no-hardcoded-uuid.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
