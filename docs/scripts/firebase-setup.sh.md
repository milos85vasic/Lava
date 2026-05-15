# `scripts/firebase-setup.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/firebase-setup.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/firebase-setup.sh — one-time Firebase project bootstrap.

Idempotent helper that:
  1. Verifies firebase-tools CLI is installed
  2. Authenticates via $LAVA_FIREBASE_TOKEN (loaded from .env, never logged)
  3. Verifies the Firebase project + Android + Web apps exist
  4. Re-fetches app/google-services.json (Android)
  5. Re-fetches lava-api-go/firebase-web-config.json (Web)
  6. Re-creates / verifies the App Distribution tester group

Usage:
  ./scripts/firebase-setup.sh                # full setup
  ./scripts/firebase-setup.sh --refresh      # only refresh config files
  ./scripts/firebase-setup.sh --invite-only  # only invite testers from .env

Constitutional bindings:
  §6.H Credential Security — token + admin key never logged or committed
  §6.J Anti-Bluff — script returns non-zero on real failures (no WARN swallow)
  §6.K Builds-Inside-Containers — operator may invoke this from a container
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/firebase-setup.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
