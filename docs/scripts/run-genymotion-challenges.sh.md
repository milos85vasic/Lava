# run-genymotion-challenges.sh

Genymotion Challenge runner (§6.AH VM path).

Script: `scripts/run-genymotion-challenges.sh`

## What it does

Genymotion runs Android inside a VM, so it satisfies §6.AH ("virtual devices /
emulators MUST run in Containers or VMs — never host-direct"). THIN GLUE:

1. Device detection + boot/stop is delegated to the Containers submodule's
   `cmd/genymotion` CLI (the §6.AG "driven by the Containers submodule"
   requirement) — never a live/physical ADB device.
2. The Challenge install + instrumentation runs through Gradle's
   `connectedDebugAndroidTest` against the resolved Genymotion adb serial.
3. Records the per-Challenge outcome as §6.I/§6.AE attestation evidence.

This is the DEBUG-variant on-VM Challenge runner; the RELEASE variant cold-start
is covered by `scripts/run-release-canary.sh` (there is no
`connectedReleaseAndroidTest` — the app's `testBuildType` is debug).

## Usage

```
./scripts/run-genymotion-challenges.sh [--test-class lava.app.challenges.ChallengeNN] [--evidence-dir <dir>]
```

## Constitutional bindings

§6.AH (VM emulator, never host-direct), §6.AG (Containers-driven device), §6.AE
(Challenge coverage), §6.I (per-AVD attestation), §6.U (no sudo).
