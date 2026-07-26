# `scripts/autonomous-qa/lib-remote.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.H (no secret echo), §6.J (failures surface), §6.R (config from .env), §6.U (no sudo)
**Classification:** project-specific

## Overview

Sourceable library implementing **Approach B (run-on-thinker)**: co-locate the
ENTIRE autonomous-QA heavy stack on `thinker.local` so build (gradle) +
containerized KVM emulator (podman) + adb + `connectedAndroidTest` + backend
all share thinker's loopback. This is the only topology where gradle's adb
reaches the emulator and the on-emulator client reaches the backend without
WAN/adb tunnels — the Containers submodule's `pkg/emulator/containerized.go`
drives LOCAL podman only (no SSH path), so the emulator MUST be local to
whichever host runs the matrix; that host is thinker (`/dev/kvm` + 16 cores).

Thin glue over `ssh` + `rsync`, keyed on the same identity that authenticates
today (`~/.ssh/id_ed25519`, confirmed via `ssh -G`).

## Functions

| Function | Purpose |
|---|---|
| `remote_preflight` | Prove reachability + key auth + podman + rsync + `/dev/kvm` before any heavy work is dispatched; non-zero (with explanation) if thinker is unfit |
| `remote_sync_repo` | rsync the repo → thinker (excludes build/.git/recordings, INCLUDES the gitignored `.env` so the Go backend's `LAVA_AUTH_*` + tracker creds reach thinker); `--delete` keeps the remote tree a faithful mirror |
| `remote_run "<cmd>"` | Run a command on thinker with cwd = the synced repo; stdout/stderr stream straight back |
| `remote_fetch <remote-rel-path> <local-dest>` | Pull curated evidence back |

## Configuration (from `.env`, gitignored — config, not secrets per §6.R)

| Variable | Default | Meaning |
|---|---|---|
| `LAVA_API_GO_REMOTE_HOST` | `thinker.local` | QA host |
| `LAVA_REMOTE_HOST_USER` | `milosvasic` | SSH user |
| `LAVA_REMOTE_HOST_KEY` | `~/.ssh/id_ed25519` | SSH identity (path only; contents never read/printed) |
| `LAVA_THINKER_QA_DIR` | `/home/<user>/lava-qa` | Remote repo location |

## SSH behavior

Shared options: `BatchMode=yes` (key-only, never prompt), bounded connect
(10 s), `StrictHostKeyChecking=accept-new`, keepalives, and a persistent
ControlMaster (`ControlPersist=300`, sockets under
`${TMPDIR:-/tmp}/lava-qa-ssh-ctrl`) so the many `remote_run` calls in a matrix
reuse one TCP/SSH session.

## Security posture

- §6.H: no secret is ever echoed. `.env` travels over the encrypted SSH
  channel, never logged. The key path is config; its contents are never read.
- §6.U: no sudo/su anywhere on either side.
- §6.J: `set -euo pipefail`; failures surface, never swallowed.

## Usage

```bash
source scripts/autonomous-qa/lib-remote.sh
remote_preflight
remote_sync_repo
remote_run "scripts/autonomous-qa/run-matrix.sh --backend goapi --subsets rutracker --queries 1080p"
remote_fetch ".lava-ci-evidence/autonomous-qa/$(date +%F)/goapi/summary.md" /tmp/summary.md
```

## Companion files

- `scripts/autonomous-qa/run-matrix.sh` — the matrix typically dispatched via `remote_run`
- `scripts/distribute-api-remote.sh` (+ its doc) — the sibling thinker remote-distribute path
