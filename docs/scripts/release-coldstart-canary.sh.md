# `scripts/autonomous-qa/release-coldstart-canary.sh`

RELEASE-variant R8 cold-start canary (§6.AA Two-Stage Distribute + §6.Z.4 cold-start mandate).

## Why it exists

The project has **no `testBuildType=release`**, so the instrumentation keystone
(`Challenge70` / `Challenge00CrashSurvivalTest`) can only run against the DEBUG
APK. The R8-minified **release** APK therefore has no on-device instrumentation
coverage — yet the §6.Z forensic anchor (Lava-Android-1.2.19-1039) was a
**release-only** R8 crash (`painterResource` on a `<layer-list>` at
`MainActivity.setContent`) that the debug build did NOT reproduce. §6.AA clause 4
names this "R8 / minification surprise" class as the load-bearing reason for the
two-stage distribute.

This canary is the release-variant equivalent of the C00 cold-start gate.

## What it does

1. Boots ONE containerized emulator via `lib-emulator.sh` (§6.AH — never
   host-direct).
2. Fresh-installs the RELEASE APK (`digital.vasic.lava.client`, no `.dev`).
3. `pm clear` + cold-launches it (`monkey … LAUNCHER 1`).
4. Waits, then captures logcat + a screenshot.
5. Emits a `verdict.json`. **PASS requires ALL THREE** (a cold-start crash breaks
   all three, so PASS cannot be bluffed by a crashed app):
   - no `FATAL EXCEPTION` for the package in logcat,
   - the app process is ALIVE (`pidof`),
   - the app's activity is the TOP resumed activity (`dumpsys activity`).

## Usage

```bash
# after ./gradlew :app:assembleRelease
scripts/autonomous-qa/release-coldstart-canary.sh \
  [--apk app/build/outputs/apk/release/app-release.apk] \
  [--evidence-dir .lava-ci-evidence/1079-release-coldstart]
```

Exit: `0` PASS · `1` FAIL (crash / not-foregrounded) · `2` setup error (missing
APK or emulator boot failure).

## Evidence

`--evidence-dir/verdict.json` (curated, tracked) records the variant, package,
APK sha256, fatal count, pid, top activity, and verdict. `raw/` (gitignored)
holds the full logcat + screenshot. The verdict.json is the release-stage §6.Z
evidence referenced by the distribute-changelog `<version>-test-evidence` file.
