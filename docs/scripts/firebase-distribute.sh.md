# `scripts/firebase-distribute.sh` — User Guide

**Last verified:** 2026-06-26 (§6.AK Phase-1 Gate 7 wiring)
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

## Phase 1 Gate 7 (§6.AK cycle-coverage) — added 2026-06-26

Closes the firebase-distribute portion of §6.AK-debt. After the §6.P/§6.AA/§6.Z
Phase-1 gates, the script invokes `scripts/check-cycle-coverage.sh` for the
version+channel being distributed (`--evidence-dir="$CHANGELOG_DIR"`, `--strict`)
and **refuses the distribute** unless EVERY CHANGELOG-claimed user-visible fix has
an EXECUTED+PASSED covering device Challenge in the §6.Z evidence file for the
SAME commit SHA. This is the gate that would have caught the 1076 incident
(`627a0d58`: a C00-only device gate while the CHANGELOG claimed search /
provider-selection / onboarding fixes). Exit mapping: `0` PASS · `1` an uncovered
claim · `2` evidence/map missing/stale/wrong-SHA. The `|| ak_rc=$?` idiom keeps
`set -e` from aborting before the §6.AK FATAL directive prints. Gates BOTH apps
(client + api-app) via the app-resolved `$CHANGELOG_DIR`. Companion hermetic test:
`tests/cycle-coverage/test_wiring.sh` (5/5, mutation-rehearsal proven); the gate's
own test is `tests/cycle-coverage/test_cycle_coverage.sh` (7/7). The cycle author
must write `<vname>-<code>-test-evidence.{md,json}` (with the `cycle-coverage:`
header + per-Challenge `challenge:` rows) and `<vname>-<code>-cycle-coverage-map.yaml`
under the channel dir for this gate to pass on a real distribute.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/firebase-distribute.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
