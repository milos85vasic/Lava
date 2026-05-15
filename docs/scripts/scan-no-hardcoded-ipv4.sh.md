# `scripts/scan-no-hardcoded-ipv4.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/scan-no-hardcoded-ipv4.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/scan-no-hardcoded-ipv4.sh — standalone §6.R IPv4 scanner.

Purpose: enforce the §6.R No-Hardcoding Mandate clause that no IPv4
literals appear in tracked source outside the exemption set.
§4.5.10 (CONTINUATION.md): the IPv4 enforcement is staged — this
scanner is the mechanical gate that lands the rule.

Exit codes:
  0 — no IPv4 violations
  1 — IPv4 violation(s) found (paths printed to stderr)

Exemptions (kept in lockstep with the §6.R clause body):
  .env.example                                — placeholder file
  .lava-ci-evidence/                          — forensic anchors + matrix evidence
  docs/**/*.md                                — design docs, plans, incident notes
  Submodules/                                 — submodules vendored at pinned hash
  *_test.go, *Test.kt, *Tests.kt, *Test.java  — synthetic test fixtures
  src/test/, src/androidTest/                 — test source roots
  fixtures/                                   — test HTML/JSON fixtures
  CHANGELOG.md                                — release notes may reference IPs in incident summaries
  *.md, *.json, *.xml, *.yml, *.yaml          — external config + docs are legitimate
                                                home for connection literals (Android
                                                network_security_config.xml whitelists LAN
                                                ranges, Grafana provisioning .yml lists service
                                                endpoints, etc.). Code that READS these files
                                                is what §6.R targets.

Loopback / docs-prefix IPs that are universally permitted (RFC 5737,
documentation-only) are filtered AFTER the file-level exemption so a
code file that legitimately uses 127.0.0.1 or 0.0.0.0 still passes —
those are not "hardcoded connection addresses" in the §6.R sense
(they cannot connect to anything but the local host or the wildcard).
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/scan-no-hardcoded-ipv4.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
