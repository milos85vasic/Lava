# `scripts/firebase-stats.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/firebase-stats.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/firebase-stats.sh — pull Crashlytics + Analytics stats for the
currently-released Lava Android build.

Reports on stdout:
  - Crashlytics: fatal counts, non-fatal counts, top 5 issues by frequency
  - Analytics: event counts for the canonical user-visible flow events
    (login, search, browse, view-topic, download-torrent)

Usage:
  ./scripts/firebase-stats.sh                    # last 7 days
  ./scripts/firebase-stats.sh --days 30          # last 30 days
  ./scripts/firebase-stats.sh --json             # machine-readable output

Note (2026-05-05): Firebase CLI does not expose Crashlytics/Analytics query
endpoints directly. This script prints the dashboard URLs for the operator
+ uses `gcloud` (if available) to pull aggregate Cloud Logging counts as
a non-bluff signal that the integration is reporting. When richer queries
are needed, the lava-api-go service will gain a /admin/firebase-stats
endpoint backed by the Firebase Admin SDK (see internal/firebase/).

Constitutional bindings:
  §6.J Anti-Bluff — script doesn't pretend to query a non-existent CLI
        endpoint; it surfaces the dashboard URL + the partial signals
        it CAN provide.
  §6.H Credential Security — token never logged.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/firebase-stats.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
