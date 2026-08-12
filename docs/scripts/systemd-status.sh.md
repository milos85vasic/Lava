# `scripts/systemd-status.sh` — User Guide

**Last verified:** 2026-08-12 (initial version)
**Inheritance:** HelixConstitution §11.4.18 + Lava §6.B (Container "Up" is not application-healthy)

## Overview

Convenience wrapper that reports the `lava-api-go` systemd `--user` unit's state **and** the underlying container's real health, in one command. This exists specifically because of §6.B: a systemd unit can report `active` (via `RemainAfterExit=yes`) while the container inside it has crash-looped — the unit's own status alone is a necessary, never sufficient, signal.

## Usage

```bash
./scripts/systemd-status.sh
```

## Output sections

1. `systemctl --user status lava-api.service` — unit-level state (active/inactive, linger dependency).
2. `loginctl show-user <you> --property=Linger` — confirms the unit can start at boot without a login session.
3. `tools/lava-containers/bin/lava-containers -cmd=status` — **the load-bearing signal**: real runtime (podman/docker), `Healthy: true/false`, LAN IP, and the live `/health` URL. This is the same status command documented in the root `CLAUDE.md` under "Run the proxy locally".

A healthy deployment shows the unit active AND `Healthy: true`. Either one alone is not sufficient evidence the API actually works — see the root CLAUDE.md §6.B forensic anchor (the `healthprobe` binary that silently failed while the container reported `Up`).
