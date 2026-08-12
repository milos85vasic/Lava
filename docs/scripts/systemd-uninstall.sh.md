# `scripts/systemd-uninstall.sh` — User Guide

**Last verified:** 2026-08-12 (initial version)
**Inheritance:** HelixConstitution §11.4.18 + Lava §6.U (No-sudo/su Mandate)

## Overview

Reverses `scripts/systemd-install.sh`: stops the `lava-api-go` systemd `--user` unit if active, disables it, removes the rendered `~/.config/systemd/user/lava-api.service` file, and reloads the systemd `--user` daemon. No sudo/su used or required.

## Usage

```bash
./scripts/systemd-uninstall.sh
```

## What it does NOT do

Does not run `loginctl disable-linger` — linger is an account-wide setting other `--user` units on the same machine may rely on, so this script leaves it untouched. Disable it manually if desired:

```bash
loginctl disable-linger
```

See `docs/scripts/systemd-install.sh.md` for the install-side counterpart.
