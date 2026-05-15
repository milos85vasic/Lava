# `scripts/distribute-api-remote.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/distribute-api-remote.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/distribute-api-remote.sh — distribute lava-api-go to a remote host.

Usage:
  ./scripts/distribute-api-remote.sh                     # default: thinker.local
  ./scripts/distribute-api-remote.sh another-host.local

The remote host MUST:
  - Be reachable via SSH at $LAVA_REMOTE_HOST_USER@$REMOTE_HOST
  - Have passwordless SSH (key-based auth) configured
  - Have rootless Podman 4.x or later installed

What it does:
  1. Loads .env (LAVA_REMOTE_HOST_USER, optional overrides).
  2. Verifies SSH connectivity to the target host.
  3. Builds the lava-api-go OCI image if releases/<version>/api-go/
     lava-api-go-<v>.image.tar is missing.
  4. Copies the OCI image tarball + thinker.local.env + thinker-up.sh
     + TLS certs to the remote host.
  5. Runs deployment/thinker/thinker-up.sh on the remote.
  6. Verifies https://$REMOTE_HOST:$PORT/health responds.

Inverse: scripts/distribute-api-remote.sh --tear-down <host> stops + removes
the remote containers + image (used at the end of this session to free
the local host of all API containers + images).

Constitutional bindings:
  §6.H — credentials never logged; .env is gitignored.
  §6.J — set -euo pipefail; no `|| echo WARN` swallow.
  §6.B — health check is a real HTTP probe, not just `podman ps`.
  §6.M — no host power-management commands; rootless Podman.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/distribute-api-remote.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
