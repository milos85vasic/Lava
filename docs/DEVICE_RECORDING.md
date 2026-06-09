# §11.4.128 Always-On Device Recording (Lava-side)

`Classification:` project-specific (the recorder target — Genymotion VM / container
emulator serials — is Lava's; the §11.4.128 mandate it implements is universal,
inherited via §6.AI / §6.AD).

This document records the Lava-side implementation of the HelixConstitution
§11.4.128 always-on device-recording mandate, tracked under **§6.AI-debt** in the
root `CLAUDE.md`. It states honestly what is implemented + verified versus what
remains **UNVERIFIED-WITHOUT-A-DEVICE** and what is still **OWED**.

## Contract (CLAUDE.md §6.AI / §11.4.128)

A background, side-effect-free, subagent-driven recorder (logcat / perf /
crash-ANR + optional screenrecord / periodic screenshot) writing raw output into
the deterministic layout:

```
<root>/YYYY-MM-DD/<state-hash>/<DEVICE>_<SERIAL>/recording_NNN/
```

- Raw output **git-ignored + codegraph-excluded**; only **curated** evidence is
  ever committed.
- Default posture is **capture-and-store**; analysis happens only at release-prep
  or on explicit operator request — the recorder does **not** analyse.
- Capture is **read-only** w.r.t. the app under test.

## Files

| File | Role |
|------|------|
| `scripts/record-device-session.sh` | The recorder. `start` / `stop` / `path` subcommands. Pure path-math functions are sourceable (no side effects when sourced). |
| `tests/device-recording/test_record_path.sh` | Hermetic falsifiability test of the deterministic path math — **no device required**. |
| `.gitignore` → `.lava-ci-evidence/device-recordings/` | Raw recordings git-ignored. |
| `.codegraph/config.json` → `.lava-ci-evidence/device-recordings/**` | Raw recordings excluded from the codegraph index (in addition to the pre-existing blanket `.lava-ci-evidence/**` exclude; the explicit entry makes the intent auditable). §6.H secret excludes left intact. |

## Device serial resolution

Mirrors `scripts/run-genymotion-challenges.sh`: explicit `--serial <s>`, else the
first token of `$LAVA_REAL_DEVICE_SERIALS`, else error (exit 2). The serial is
expected to belong to a **Containers-submodule-driven Genymotion VM or container
emulator** (§6.AG / §6.AH: never a host-direct emulator, never a borrowed live
ADB device).

## Read-only guarantee

The recorder only ever runs observing adb verbs: `adb logcat -v threadtime`
(**without** `-c`, which would clear the buffer — a side effect), `adb shell
screenrecord`, `adb exec-out screencap`, and `adb shell getprop`. It never runs
`pm clear`, `am force-stop`, or any install/write verb against the app under test.

## What is VERIFIED (no device needed)

Captured evidence — `bash tests/device-recording/test_record_path.sh`:

```
test: §11.4.128 deterministic path layout
  device-dir layout: PASS
  first index = 001: PASS
  idempotent (no creation -> same NNN): PASS
  NNN increments 001->002->003: PASS
  max+1 with gap (005 -> 006): PASS
  date override: PASS
ALL PASS — §11.4.128 path layout verified (no device required)
```

- `bash -n scripts/record-device-session.sh` → `BASH_SYNTAX_OK` (parses clean).
- `record-device-session.sh path …` dry-run prints the correct deterministic dir
  with **no device attached**, e.g.
  `…/2026-06-09/deadbee/Pixel-8_R5CW33CBVQV/recording_001`.
- Codegraph config remains valid JSON after the exclude insertion; all §6.H
  secret excludes (`**/.env`, `**/keystores/**`, `**/google-services.json`,
  `**/firebase-admin-*.json`, `**/secrets/**`, …) preserved.

### Bluff-Audit (§6.N / Seventh Law clause 1)

```
Bluff-Audit: tests/device-recording/test_record_path.sh
  Mutation: scripts/record-device-session.sh rds_next_index
            `printf '%03d' "$(( max + 1 ))"` → `$(( max + 0 ))`
  Observed-Failure: FAIL: first recording dir. want […/recording_001]
                    got […/recording_000]  (exit 1)
  Reverted: yes
```

## What is UNVERIFIED-WITHOUT-A-DEVICE

The actual device-bound capture — that `adb logcat` / `screenrecord` / `screencap`
against a running Genymotion VM produce non-empty, well-formed artifacts in the
layout — is **NOT verified by this agent**: the Genymotion VM belongs to the
operator and was not available. The `start` / `stop` subcommands parse clean and
their adb invocations are read-only by construction, but **no real session has
been captured**. Do **not** read this work as "it recorded a real session."

## OWED for full §11.4.128 closure (§6.AI-debt)

1. **Operator-run verification** of `start` → exercise the app on a real
   Containers-driven Genymotion VM → `stop`, confirming `logcat.txt` / `screen.mp4`
   / `shot_*.png` are non-empty and live under the deterministic dir. Capture that
   run's evidence and link it here.
2. **`perf` / crash-ANR enrichment** beyond raw logcat (e.g. `dumpsys` snapshots,
   ANR-trace pulls from `/data/anr/`) — currently only logcat + screen captures.
3. **Subagent-driven always-on wiring** — auto-start the recorder around every
   Genymotion challenge run (hook into `scripts/run-genymotion-challenges.sh`),
   and a **curation step** that promotes a subset of raw recordings into committed
   evidence at release-prep.
4. **`CM-COVENANT-114-128-PROPAGATION`** literal-anchor gate (per §6.AI-debt).
