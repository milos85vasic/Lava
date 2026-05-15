# `scripts/scan-no-hardcoded-hostport.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/scan-no-hardcoded-hostport.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/scan-no-hardcoded-hostport.sh — standalone §6.R host:port scanner.

Purpose: enforce the §6.R No-Hardcoding Mandate clause that no
`host:port` literals in URLs appear in tracked source outside the
exemption set. §4.5.10 (CONTINUATION.md): staged enforcement — this
is the mechanical gate that lands the rule.

Exit codes:
  0 — no host:port violations
  1 — host:port violation(s) found (paths printed to stderr)

Pattern: `<scheme>://<host>:<numeric-port>` where scheme is one of
http/https/ws/wss and the port is 2-5 digits. Matching only URL-shaped
literals avoids false positives on `Map<String, Int>` declarations or
`key:value` JSON snippets in comments.

Exemptions (lockstep with §6.R clause body):
  .env.example, .lava-ci-evidence/, Submodules/, tests, fixtures/,
  CHANGELOG.md, *.md, *.json, *.xml, *.yml, *.yaml — external config
  and docs are legitimate homes for these literals.

Loopback hosts (localhost / 127.x.x.x / 0.0.0.0) are filtered AFTER
the file-level exemption — those are not "hardcoded connection
addresses" in the §6.R sense.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/scan-no-hardcoded-hostport.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
