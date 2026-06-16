# record-challenge-video.sh

Records + validates ONE Compose UI Challenge on the Genymotion VM and delivers
the SUCCESS video to the operator's home dir.

Script: `scripts/record-challenge-video.sh`

## What it does (thin glue, §6.AH VM path, §6.U no-sudo)

1. Resolves the Genymotion VM adb serial via the Containers submodule
   `cmd/genymotion` CLI (§6.AG — the VM is an authorized non-host-direct surface
   per §6.AH; NEVER a live/physical ADB device).
2. Starts `screenrecord` on the VM, runs the named Challenge via
   `connectedDebugAndroidTest`, stops the recording.
3. On a PASSing Challenge, pulls the recording and delivers it to the operator's
   home dir (`$HOME` / `$HOME/Downloads`). Only PASS videos are delivered (a
   failing Challenge's recording is not presented as success — §6.J).

## Usage

```
./scripts/record-challenge-video.sh <ChallengeNN_Name>
```

## Constitutional bindings

§6.AH (VM/container emulator, never host-direct), §6.AG (Containers-driven device
resolution), §6.U (no sudo), §6.J (only real PASS videos delivered), §11.4.128
(device-recording discipline).
