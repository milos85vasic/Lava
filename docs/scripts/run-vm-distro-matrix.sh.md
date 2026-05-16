# `scripts/run-vm-distro-matrix.sh` — User Guide

**Last verified:** 2026-05-16 (Phase 6a + 6b §11.4.29 snake_case migration: scripts/run-vm-distro-matrix.sh path-references updated from `Submodules/` to `submodules/`)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/run-vm-distro-matrix.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/run-vm-distro-matrix.sh — cross-OS distro matrix wrapper.
Drives cmd/vm-matrix against (alpine, debian, fedora) × x86_64 = 3
configs. Each VM starts proxy.jar + lava-api-go in background and
probes 4 endpoints (proxy_health, proxy_search, goapi_health,
goapi_metrics). Post-processing rejects when any of 4×3=12 booleans
is false.

Bluff vector this catches: distro-specific runtime behavior — a
proxy or Go binary that runs cleanly on alpine but fails on debian
(musl vs glibc, systemd vs OpenRC, packaged JRE differences). The
Sixth Law clause 4 acceptance gate ("works for a real user end-to-
end") fails silently if we only test on one distro family.

Pre-requisites:
  - proxy/build/libs/app.jar exists (./gradlew :proxy:buildFatJar)
  - lava-api-go/build/lava-api-go binary exists
  - tools/lava-containers/vm-images.json has REAL hashes (placeholder
    zeros reject in pkg/cache.Store.Get())

Exit codes:
  0 — all 4 probes succeeded on all 3 distros (12/12 true)
  1 — at least one probe failed OR a probe-output.json is missing
  2 — configuration error (missing inputs)
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/run-vm-distro-matrix.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
