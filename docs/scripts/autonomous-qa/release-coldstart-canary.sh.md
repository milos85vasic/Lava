# `scripts/autonomous-qa/release-coldstart-canary.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.AA (two-stage distribute), §6.Z.4 (pre-distribute test execution), §6.AH (containerized emulator)
**Classification:** project-specific

## Overview

The §6.AA / §6.Z.4 **RELEASE-variant R8 cold-start canary**.

The project has NO `testBuildType=release`, so the instrumentation keystone
(Challenge70 / Challenge00) cannot run against the R8-minified release APK.
This canary is the release-variant equivalent of the C00 cold-start gate: it
boots a containerized emulator (via `lib-emulator.sh`, §6.AH), fresh-installs
the RELEASE APK, `pm clear`s it, cold-launches it, and confirms it SURVIVES —
the exact failure class of the **1.2.19-1039 forensic anchor** (a release-only
R8 `painterResource` crash at `MainActivity.setContent` that the debug build
did not reproduce, and that hit every cold launch on a real device).

## Anti-bluff verdict

ALL THREE must hold for PASS (a cold-start crash breaks all three):

1. NO `FATAL EXCEPTION` for the package in logcat after launch,
2. the app process is ALIVE (`pidof`) — a cold-start crash kills it,
3. the app's activity is the TOP resumed activity (`dumpsys`) — not just a
   lingering background service.

## Usage

```bash
scripts/autonomous-qa/release-coldstart-canary.sh [--apk <path>] [--evidence-dir <dir>]
```

| Flag | Default | Meaning |
|---|---|---|
| `--apk <path>` | `app/build/outputs/apk/release/app-release.apk` | The release APK under test |
| `--evidence-dir <dir>` | `.lava-ci-evidence/1079-release-coldstart` | Curated evidence destination |

Note the target package is `digital.vasic.lava.client` — the release variant
carries NO `.dev` suffix.

## Evidence

```
<evidence-dir>/
  verdict.json                      # variant/package/apk_sha256/fatal_count/pid/top_activity/verdict/note
  raw/release-coldstart-logcat.txt  # full logcat dump after the 20 s settle
  raw/release-coldstart.png         # screencap after launch
```

The APK's sha256 is recorded in `verdict.json` so the canary is pinned to the
exact artifact about to be distributed (§6.Z).

## Exit codes

- `0` — PASS (survived cold start, foregrounded)
- `1` — FAIL (crash / not foregrounded)
- `2` — setup error (release APK missing, emulator boot failure)

## Companion files

- `scripts/autonomous-qa/lib-emulator.sh` (+ its doc) — the containerized emulator lifecycle
- `scripts/run-release-canary.sh` (+ its doc) — the sibling release-canary runner
- `docs/scripts/firebase-distribute.sh.md` — the §6.AA two-stage distribute flow this gates
- `.lava-ci-evidence/` — §6.Z distribute evidence packs
