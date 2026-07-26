# `scripts/autonomous-qa/record-qa-session.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.J / §6.Z (anti-bluff evidence)
**Classification:** project-specific

## Overview

Records the Android emulator screen during an autonomous QA run and saves a
**verified** MP4 to `~/Downloads/lava-qa-<timestamp>-<scenario>.mp4`.

The recorder uses `adb shell screenrecord` in the background. Because
screenrecord is capped at 180 seconds per invocation, long tests are recorded
as sequential chunks that are concatenated with ffmpeg after the test command
exits. Chunks are staged under `/tmp` so large captures never bloat the repo.

## Anti-bluff contract (§6.J / §6.Z)

The script **refuses to claim success** unless the recorded file is non-empty
AND ffmpeg confirms it contains a video stream with at least one decoded frame.
An empty/corrupt/unprobed recording is exit 2, never a silent "recorded".

## Usage

```bash
scripts/autonomous-qa/record-qa-session.sh --serial <serial> --scenario <name> [options]
```

| Flag | Required | Default | Meaning |
|---|---|---|---|
| `--serial <serial>` | yes | — | adb serial of the running emulator/device |
| `--scenario <name>` | yes | — | short scenario slug for the filename (normalized to lowercase alphanumerics + hyphen) |
| `--test-command <cmd>` | no | `sleep 5` | shell command to run while recording |
| `--output-dir <dir>` | no | `~/Downloads` | directory for the final MP4 |
| `--bit-rate <bps>` | no | `4000000` | screenrecord bit-rate |
| `--chunk-sec <secs>` | no | `180` | chunk length; MUST be ≤ 180 (screenrecord limit) |
| `-h\|--help` | no | — | show help |

Environment: `ANDROID_HOME` (adb location), `FFMPEG` (ffmpeg path override).

## Exit codes

- `0` — test command passed AND video verified
- `1` — test command failed BUT video was verified (the recording is still
  valid evidence of the failure)
- `2` — setup error (missing args, adb/ffmpeg unavailable, device unreachable)
  or video verification failed

## Examples

```bash
# Record a Challenge run end-to-end
scripts/autonomous-qa/record-qa-session.sh \
  --serial 127.0.0.1:42317 --scenario search-flow \
  --test-command './gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=lava.app.challenges.Challenge01*'

# Quick 5-second smoke recording
scripts/autonomous-qa/record-qa-session.sh --serial emulator-5554 --scenario smoke
```

## Companion files

- `scripts/autonomous-qa/run-iteration.sh` — embeds the same chunked-recording pattern inline for matrix iterations
- `scripts/autonomous-qa/vision-analyze.sh` — analyzes the recordings this produces
- `scripts/record-challenge-video.sh` / `scripts/record-device-session.sh` (+ their docs) — the sibling recording paths
