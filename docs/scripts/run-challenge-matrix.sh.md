# `scripts/run-challenge-matrix.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, 31st §6.L invocation)
**Inheritance:** HelixConstitution §11.4.18 + Lava §6.AE.6 + §6.X (Container-Submodule Emulator Wiring) + §6.I (Multi-Emulator Container Matrix)

## Overview

Operator entry point for §6.AE gate-mode Challenge Test runs. Pre-bakes the §6.AE.2 minimum AVD matrix (API 28 / 30 / 34 / latest stable × phone + tablet) and delegates to `Submodules/Containers/cmd/emulator-matrix --runner=containerized` per §6.X.

## Honest pre-flight

The script DETECTS the §6.X-debt darwin/arm64 host gap (no `/dev/kvm` available to podman containers; macOS HVF not exposed to podman) and, when detected, REFUSES to claim a successful gate run. Instead it:

1. Validates arguments + matrix minimum
2. Writes `<evidence-dir>/host-preflight.json` with the host-gap classification
3. Exits 2 (NOT 0) — gate-host ineligible

Per §6.J/§6.L: no false-pass; honest unblock report. Real gate runs require a Linux x86_64 + KVM host.

## Usage

```bash
# Run ALL Challenges on the §6.AE.2 minimum matrix
bash scripts/run-challenge-matrix.sh

# Run a specific Challenge
bash scripts/run-challenge-matrix.sh \
  --test-class lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest

# Add TV-class AVD (when feature touches TvActivity)
bash scripts/run-challenge-matrix.sh --add-tv

# Add foldable AVD
bash scripts/run-challenge-matrix.sh --add-foldable

# Override "latest stable" API level
bash scripts/run-challenge-matrix.sh --latest-api 36

# Skip APK rebuild
bash scripts/run-challenge-matrix.sh --no-build
```

## Inputs

| Arg | Description |
|---|---|
| `--test-class <fqn>` | Specific Challenge to run (default: all under `lava.app.challenges`) |
| `--evidence-dir <dir>` | Output directory (default: dated under `.lava-ci-evidence/`) |
| `--no-build` | Skip the `:app:assembleDebug` step |
| `--latest-api <N>` | Override the "latest stable" API level (default: 36) |
| `--add-tv` | Add a TV-class AVD to the matrix |
| `--add-foldable` | Add a foldable AVD |

## §6.AE.2 mandatory minimum matrix

```
CZ_API28_Phone:28:phone
CZ_API30_Phone:30:phone
CZ_API34_Phone:34:phone
CZ_API34_Tablet:34:tablet
CZ_API<latest>_Phone:<latest>:phone
```

Plus TV / foldable when explicitly requested by `--add-tv` / `--add-foldable`.

Sub-minimums are permitted for development iteration; the gate row's `gating: true` flag is only set when the full minimum is satisfied + every config dimension (theme/locale/density per §6.AE.2) is covered.

## Outputs

- `<evidence-dir>/host-preflight.json` — host-gap classification (always written)
- `<evidence-dir>/real-device-verification.{md,json}` — per-AVD attestation rows (only on Linux x86_64 + KVM gate-host)

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Matrix completed; all AVDs passed |
| 1 | At least one AVD failed boot OR at least one test failed (real Containers CLI exit) |
| 2 | Gate-host ineligible (darwin/arm64 OR no KVM); honest unblock-needed report written |

## Cross-references

- `Submodules/Containers/cmd/emulator-matrix` (the underlying runner)
- `tools/lava-containers/vm-images.json` (matrix manifest)
- `scripts/run-emulator-tests.sh` (older sister-glue; same delegation, different default arguments)
- `.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json` (the standing §6.X-debt incident)
- Lava `CLAUDE.md` §6.AE + §6.X + §6.I
