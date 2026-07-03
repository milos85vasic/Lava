# `scripts/autonomous-qa/run-nav-challenges.sh`

Boots ONE containerized-KVM emulator (via `lib-emulator.sh` — the same §6.AH path
the autonomous-QA matrix uses) and runs the §6.AK cycle-coverage-map's supporting
**navigation Challenges** (C24 / C46 / C55) against the already-built debug APK,
then tears the emulator down.

## Why it exists

The §6.AK cycle-coverage-map for a client cycle can list supporting nav
Challenges (back-navigation, search-timeout interrupt, bottom-nav switching)
alongside the keystone. `run-matrix.sh` only runs `Challenge70`; this script runs
the *other* Challenges named in the coverage-map so each one gets a real
per-Challenge device pass/fail in the §6.Z evidence.

## Usage

```bash
# after the debug APK is built (app/build/outputs/apk/debug/app-debug.apk)
scripts/autonomous-qa/run-nav-challenges.sh <evidence-dir> <fqn>[ <fqn>...]

# example
scripts/autonomous-qa/run-nav-challenges.sh \
  .lava-ci-evidence/1079-nav \
  lava.app.challenges.Challenge24OnboardingBackNavigationTest \
  lava.app.challenges.Challenge46SearchTimeoutInterruptTest \
  lava.app.challenges.Challenge55MainScaffoldBottomNavSwitchesTest
```

Requires a pre-built `app-debug.apk` (exits 2 if missing — same pre-built-APK
contract as `run-iteration.sh`). Installs `digital.vasic.lava.client.dev`.

## Evidence

`<evidence-dir>/` gets the JUnit XML + a per-test verdict (curated, tracked);
`<evidence-dir>/raw/` (gitignored) holds the logcat + screen recording.

## Related

- `run-matrix.sh` / `run-iteration.sh` — the keystone (`Challenge70`) runner.
- `release-coldstart-canary.sh` — the release-variant R8 cold-start gate.
- Forensic anchor: `.lava-ci-evidence/sixth-law-incidents/2026-06-30-nav-challenges-first-device-run-brittleness.json`.
