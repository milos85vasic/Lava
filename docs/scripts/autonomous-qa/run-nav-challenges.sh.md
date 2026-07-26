# `scripts/autonomous-qa/run-nav-challenges.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.AK (cycle coverage), §6.Z (device evidence), §6.AH (containerized emulator)
**Classification:** project-specific

## Overview

Boots ONE containerized-KVM emulator (via `lib-emulator.sh` — the same path the
autonomous-QA matrix uses) and runs the §6.AK cycle-coverage-map's supporting
nav Challenges (C24 / C46 / C55) against the already-built debug APK, then
tears the emulator down. Captures the JUnit XML + per-test verdict so the §6.Z
device evidence can record each Challenge's pass/fail with a real device.

## Usage

```bash
scripts/autonomous-qa/run-nav-challenges.sh <evidence-dir> <fqn>[ <fqn>...]

# Example: the nav-Challenge trio
scripts/autonomous-qa/run-nav-challenges.sh \
  .lava-ci-evidence/nav-challenges/$(date +%F) \
  lava.app.challenges.Challenge24TopicNavigationTest \
  lava.app.challenges.Challenge46SearchNavigationTest \
  lava.app.challenges.Challenge55ForumNavigationTest
```

The debug APK must already exist at
`app/build/outputs/apk/debug/app-debug.apk` (missing → exit 2). Class
filtering happens via the instrumentation runner argument
(`-Pandroid.testInstrumentationRunnerArguments.class=<comma-separated FQNs>`),
NOT gradle's `--tests` — mirroring `run-iteration.sh`.

## Harness-side setup (why the extra steps)

- **Fresh install** (uninstall + `install -r`) — clean state.
- **`POST_NOTIFICATIONS` pre-granted** — otherwise LeakCanary (debug-only)
  launches its RequestPermissionActivity mid-test → the system
  GrantPermissionsActivity pops OVER MainActivity → the next Compose query
  throws "No compose hierarchies found". (API 33+ only; ignored on older.)
- **Keyguard dismissed + screen woken BEFORE instrumentation** — a cold-booted
  emulator can present the lockscreen, so the first Challenge's MainActivity
  has no reachable compose hierarchy (the 1077 keyguard incident;
  `run-genymotion-challenges.sh` applies the same fix).
- **Logcat captured** to `<evidence-dir>/raw/logcat.txt` for the whole run.

## Evidence + exit

```
<evidence-dir>/
  *.xml                 # curated JUnit results (copied from androidTest-results/connected)
  raw/logcat.txt
  raw/gradle-nav.log
```

Exit code is the gradle `connectedDebugAndroidTest` return code (`0` = all
Challenges passed); `2` = setup error (missing APK/emulator). The emulator is
always torn down (EXIT trap).

## Companion files

- `scripts/autonomous-qa/lib-emulator.sh` (+ its doc) — the containerized emulator lifecycle
- `scripts/run-genymotion-challenges.sh` / `scripts/run-challenge-matrix.sh` (+ their docs) — the sibling Challenge runners
- `scripts/autonomous-qa/aggregate-evidence.sh` — the §6.AK cycle-coverage rollup these results feed
