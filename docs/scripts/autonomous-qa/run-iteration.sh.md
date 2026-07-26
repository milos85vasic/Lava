# `scripts/autonomous-qa/run-iteration.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.J / §6.L (anti-bluff), §6.AK (device-gate auth), §6.H (credential handling)
**Classification:** project-specific

## Overview

Runs **one QA iteration** against an already-booted emulator + already-up
backend (the matrix cell executed by `run-matrix.sh`):

1. **FRESH client install** (uninstall + `install -r`) — clean onboarding state
2. Start recording: cleared `logcat` + chunked `screenrecord` (180 s cap/chunk)
3. Run `Challenge70AutonomousQaProviderMatrixTest` parameterized via gradle
   `connectedDebugAndroidTest` instrumentation arguments
4. Stop recording (+ a best-effort `final-screen.png` still while the app is up)
5. Parse the JUnit verdict → curated `verdict.json`

Raw media/logs go to `<evidence-dir>/raw/` (gitignored); `verdict.json` +
`junit.xml` are curated.

## Usage

```bash
scripts/autonomous-qa/run-iteration.sh \
  --backend goapi|apiapp --providers <csv> --query <str> \
  --serial <adb-serial> --api-url <url> --evidence-dir <dir>
```

All of `--backend`, `--providers`, `--query`, `--serial`, `--evidence-dir` are
required; `--api-url` defaults to `https://127.0.0.1:8443`.

## The goapi auth key (§6.AK / §6.H)

For the `goapi` backend the client must authenticate `/v1` with the Go API's
configured Lava-Auth key. The script derives it from the same root `.env` the
compose stack feeds the container (`LAVA_AUTH_ACTIVE_CLIENTS: name:uuid`) —
it needs the UUID only, NOT the HMAC secret. The derived key is
credential-equivalent and is **never echoed**. Without an entry the iteration
runs keyless (and `/v1` will 401 — recorded honestly).

## Verdict decision table (anti-bluff)

Challenge70 logs `C70-RESULT ... DOWNLOAD-OK` only after the user-visible
download/magnet affordance is confirmed on screen — hard evidence the real user
flow reached the download. Separately, the test's `MainActivity` teardown can
crash with the known-open upstream defect **LVA-008** (a process-FATAL
`IllegalStateException` at activity destroy) that fails the JUnit run without
being a product defect. Signals grepped from logcat + JUnit XML:

- `marker_download_ok` — the on-screen download affordance was confirmed
- `teardown_known_lva008` — logcat/JUnit contains BOTH LVA-008 phrases
- `other_failure_signal` — ANY failure unrelated to LVA-008 (explicit C70
  download-step failure marker, or any non-LVA-008 `AssertionError`)

| Condition | Verdict |
|---|---|
| `gradle_rc=0`, 0 failures/errors, `tests>0`, not all skipped | **PASS** |
| `gradle_rc=0` but `tests=0` | **FAIL** — a green run with zero executed tests is a bluff by construction |
| all tests skipped | **SKIP** |
| marker **AND** lva008-teardown **AND** no other failure signal | **PASS** (PASS-via-marker; all three MUST hold) |
| anything else | **FAIL** |

A missing client APK writes a FAIL `verdict.json` immediately (never leaves a
stale PASS behind — the 2026-07-03 incident).

## Exit codes

- `0` — verdict PASS
- `1` — verdict FAIL/SKIP
- `2` — usage error or setup error (e.g. missing APK; a FAIL verdict is written)

## `verdict.json` fields

`backend`, `providers`, `query`, `serial`, `gradle_rc`, `tests`, `failures`,
`errors`, `skipped`, `marker_download_ok`, `teardown_known_lva008`,
`other_failure_signal`, `verdict`, `note`, `junit_xml`, `raw_dir`.

## Companion files

- `scripts/autonomous-qa/run-matrix.sh` — the caller
- `scripts/autonomous-qa/lib-summary.sh` — parses these verdict files back out
- `scripts/autonomous-qa/vision-analyze.sh` — analyzes the `raw/` recordings
