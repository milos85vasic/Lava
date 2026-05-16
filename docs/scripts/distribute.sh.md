# `scripts/distribute.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/distribute.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/distribute.sh — operator's single-command distribute flow.

Rebuilds every artifact (Android debug + release APKs, Ktor proxy fat
JAR, lava-api-go static binary, lava-api-go OCI image), then:

  * Uploads Android APKs to Firebase App Distribution + invites testers.
  * Distributes the Go API to the configured remote host (default:
    thinker.local) via the Containers-submodule-driven remote pipeline
    and tears down the local API stack so the workstation no longer
    runs Postgres + the Go API.

Usage:
  ./scripts/distribute.sh                  # rebuild + distribute everything
  ./scripts/distribute.sh --android-only   # rebuild + distribute Android only
  ./scripts/distribute.sh --api-only       # rebuild + distribute Go API only
  ./scripts/distribute.sh --no-rebuild     # skip rebuild, distribute existing artifacts
  ./scripts/distribute.sh --release-only   # only release APK (skip debug)
  ./scripts/distribute.sh --debug-only     # only debug APK
  ./scripts/distribute.sh --no-teardown    # leave local API containers running

Architecture (per Decoupled Reusable Architecture rule):

  * Generic remote-host SSH + container-orchestration primitives live in
    `submodules/containers/pkg/remote/` (the vasic-digital/Containers
    submodule). Lava's distribute scripts are thin glue that:
      1. Build artifacts via build_and_release.sh (which itself routes
         through the Containers build path).
      2. Invoke `scripts/distribute-api-remote.sh` for the SSH+scp+
         podman-load+boot dance on the remote.
      3. Drive Firebase App Distribution for the Android side.

  * The Containers submodule's `bin/boot` CLI is built on demand (so
    pinned hash failures show up early, before any remote operation).

Constitutional bindings:
  §6.J Anti-Bluff — propagates failures via set -euo pipefail
  §6.K Builds-Inside-Containers — build_and_release.sh routes through
        the container path for release-tagged builds
  §6.H Credential Security — all Firebase tokens + remote SSH config
        loaded from .env (gitignored)
  §6.P Distribution Versioning + Changelog Mandate — both the Firebase
        and the remote-distribute scripts gate on §6.P.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/distribute.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
