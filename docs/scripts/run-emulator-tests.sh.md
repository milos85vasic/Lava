# `scripts/run-emulator-tests.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/run-emulator-tests.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/run-emulator-tests.sh — Lava-side thin glue around the
Containers submodule's cmd/emulator-matrix CLI (Phase 3.2 of the
2026-05-04 pending-completion plan; closes the constitutional 6.K-debt
entry in CLAUDE.md).

Per clauses 6.I (Multi-Emulator Container Matrix) + 6.K (Builds-
Inside-Containers Mandate) + the Decoupled Reusable Architecture
rule: the matrix-orchestration capability lives in
`vasic-digital/Containers/pkg/emulator/`. This script:

  1. Builds the cmd/emulator-matrix binary from the pinned submodule
     (./Submodules/Containers).
  2. Builds the Lava debug APK if requested.
  3. Invokes the binary with Lava-specific arguments: AVD list, test
     class, evidence directory.

All matrix logic — boot, install, run, teardown, attestation file
writing — is owned by the Containers submodule. New AVD form factors,
QEMU support, and other-OS emulators are extension points there, NOT
here.

Usage:

  ./scripts/run-emulator-tests.sh \
      [--test-class lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest] \
      [--avds CZ_API28_Phone:28:phone,CZ_API30_Phone:30:phone,CZ_API34_Phone:34:phone,Pixel_9a:36:phone] \
      [--evidence-dir .lava-ci-evidence/2026-05-04-matrix] \
      [--image-manifest tools/lava-containers/vm-images.json] \
      [--no-build]

Environment:
  ANDROID_SDK_ROOT or ANDROID_HOME — points to the Android SDK

Exit codes:
  0  matrix passed (every AVD booted, every test passed)
  1  matrix failed (at least one AVD failed)
  2  configuration error (missing flags, missing SDK, etc.)
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/run-emulator-tests.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
