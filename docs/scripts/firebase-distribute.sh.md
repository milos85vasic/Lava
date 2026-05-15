# `scripts/firebase-distribute.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/firebase-distribute.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/firebase-distribute.sh — upload built artifacts to Firebase App
Distribution and invite testers loaded from .env.

Replaces the local releases/ delivery flow as the canonical operator
distribution channel (operator directive 2026-05-05).

Usage:
  ./scripts/firebase-distribute.sh                    # debug + release APKs
  ./scripts/firebase-distribute.sh --debug-only       # only debug APK
  ./scripts/firebase-distribute.sh --release-only     # only release APK
  ./scripts/firebase-distribute.sh --release-notes "<text>"   # custom notes

Inputs:
  .env  (gitignored) — LAVA_FIREBASE_TOKEN, project + app IDs, tester emails
  releases/<version>/android-debug/*.apk
  releases/<version>/android-release/*.apk

Outputs:
  App Distribution release at the Firebase Console under
    project $LAVA_FIREBASE_PROJECT_ID, app $LAVA_FIREBASE_ANDROID_APP_ID.
  3 testers receive an email invite (per .env LAVA_FIREBASE_TESTERS_*).

Constitutional bindings:
  §6.H Credential Security — tokens read from .env, never echoed
  §6.J Anti-Bluff — propagates real failures via set -euo pipefail; no WARN swallow
  §6.G End-to-end provider operational verification — distribute step is the
        hand-off the operator's manual real-device pass exercises against.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/firebase-distribute.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
