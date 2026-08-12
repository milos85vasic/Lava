# `scripts/systemd-install.sh` — User Guide

**Last verified:** 2026-08-12 (initial version — systemd `--user` wiring for lava-api-go)
**Inheritance:** HelixConstitution §11.4.18 + Lava §6.U (No-sudo/su Mandate) + §6.T.2 (Resource Limits)

## Overview

Installs the `lava-api-go` container service (`start.sh` / `stop.sh`) as a **systemd `--user` unit**, so it can be started/stopped via `systemctl --user` and — via `loginctl enable-linger` — survive to boot time without an active login session. Every step runs in the invoking user's own account scope. No `sudo`/`su` is used or required anywhere in this script, per §6.U.

`loginctl enable-linger` (no argument, targeting the calling user's own account) is a config-safe, non-destructive logind operation — it is not in the CLAUDE.md Forbidden Command List (which covers `suspend`/`hibernate`/`poweroff`/`kill-user`/`kill-session`/etc.), and the pre-push guard hook (`scripts/hooks/guard-forbidden-commands.sh`) only blocks that specific dangerous-subcommand set, not `enable-linger`.

## Why `oneshot` + `RemainAfterExit`

`start.sh` brings up the `lava-api-go` container (via `tools/lava-containers`) and then exits — it is not itself a long-running foreground process. The rendered unit (`systemd/user/lava-api.service.template`) therefore uses `Type=oneshot` with `RemainAfterExit=yes`: `systemctl --user` considers the unit "active" once `start.sh` exits 0, and `ExecStop=stop.sh` tears the container back down on `systemctl --user stop`.

## Usage

```bash
./scripts/systemd-install.sh            # render unit, daemon-reload, enable (does not start)
./scripts/systemd-install.sh --start    # same, then start immediately
```

What it does, in order:
1. Renders `systemd/user/lava-api.service.template` to `~/.config/systemd/user/lava-api.service`, substituting the repo's absolute path for `__LAVA_REPO_ROOT__`.
2. `systemctl --user daemon-reload`
3. `systemctl --user enable lava-api.service`
4. Checks `loginctl show-user <you> --property=Linger`; if not already `yes`, runs `loginctl enable-linger` (no sudo — operates on the caller's own account only).
5. With `--start`, also runs `systemctl --user start lava-api.service`.

## Verifying it worked

```bash
./scripts/systemd-status.sh
```

Per CLAUDE.md §6.B ("container 'Up' is not application-healthy"), a green `systemctl --user status` only proves the unit ran `start.sh` to completion — it does NOT prove the API is actually serving traffic. `systemd-status.sh` additionally calls `tools/lava-containers/bin/lava-containers -cmd=status`, which reports the real container health (`Healthy: true/false`) and the live `/health` URL — that second line is the load-bearing signal.

## Reversing

```bash
./scripts/systemd-uninstall.sh
```

Stops the unit if active, disables it, removes the rendered unit file, and reloads the systemd `--user` daemon. Does not touch linger (an account-wide setting other `--user` units may depend on) — disable it separately with `loginctl disable-linger` if desired.

## Falsifiability note

This is infrastructure glue, not a testable code path in the Anti-Bluff Pact sense (no production Kotlin/Go logic to mutate). Its correctness is verified operationally: `systemctl --user status` reports `active (exited)` with `RemainAfterExit`, and `lava-containers -cmd=status` reports `Healthy: true` after `--start`.
