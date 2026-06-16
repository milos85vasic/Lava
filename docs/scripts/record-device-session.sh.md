# record-device-session.sh

§11.4.128 always-on device-recorder (Lava-side glue).

Script: `scripts/record-device-session.sh`

## Contract (CLAUDE.md §6.AI / §6.AI-debt / HelixConstitution §11.4.128)

A background, side-effect-free, subagent-driven recorder (logcat / perf /
crash-ANR + optional screenrecord/screenshot) that writes raw output into the
deterministic layout:

```
<root>/YYYY-MM-DD/<state-hash>/<DEVICE>_<SERIAL>/recording_NNN/
```

## What it does

1. Resolves the target device/VM serial (Containers-orchestrated VM/emulator per
   §6.AG/§6.AH — never a live/physical device).
2. Captures the analysable streams (logcat, performance metrics, crash/ANR) in
   the background, non-invasively (bounded sampling, observer-effect budget).
3. Writes raw recordings to the deterministic layout; raw output is git-ignored +
   code-intelligence-excluded — only curated evidence is committed, at release
   prep (§11.4.128).

## Usage

```
./scripts/record-device-session.sh [--serial <adb-serial>] [--root <recording-root>]
```

## Constitutional bindings

§11.4.128 (always-on device recording), §6.AI (Lava adoption + §6.AI-debt),
§6.AG/§6.AH (Containers/VM-driven device), §6.J (recorder health is captured
evidence, not a claimed-running bluff).
