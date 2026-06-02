# `scripts/check-emulator-runner-tag.sh` — User Guide

**Last verified:** 2026-06-02 (anti-forgetting enforcement cycle)
**Inheritance:** §6.X clause (c) (Container-Submodule Emulator Wiring Mandate); HelixConstitution §11.4.18 (script docs)
**Classification:** universal

## Overview

Makes §6.X enforcement clause (c) mechanical at the `check-constitution.sh` /
pre-push layer: every **NEW** emulator/Challenge attestation evidence file that
records emulator execution MUST declare `runner: containers-submodule` (or
`runner=containers-submodule`). A new evidence file that records an emulator run
with a raw/host-direct runner tag — or NO runner tag — FAILS.

Previously clause (c) was paper-only ("rows lacking this declaration are
rejected by `scripts/tag.sh`"). This scanner closes the anti-forgetting gap on
the NEXT bad file at pre-push time.

## Scope — going-forward, not retroactive

The gate flags only evidence files that are **newly added relative to git
HEAD** (staged additions under `.lava-ci-evidence/` + untracked files there).
Pre-existing committed evidence is grandfathered:

- many historical files predate §6.X (added 2026-05-13); and
- several document the §6.X-RESOLVED macOS `host-direct + HVF` gate path (a
  Linux container cannot reach HVF/`/dev/kvm`, so host-direct+HVF IS the macOS
  gate runner per the §6.X 67th-cycle resolution).

Retroactively failing committed history is not the job (and the task constraint
forbids touching real evidence). Enforcing the tag on the next file is.

## "Records emulator execution" heuristic

A file is in scope when it mentions any of (case-insensitive):
`adb_devices_state`, `boot_seconds`, `connectedAndroidTest`,
`connectedDebugAndroidTest`, `emulator-matrix`. Files without these markers
(design docs, host-stability incidents, bluff-hunt logs) are not flagged.

## Accepted runner tag

`runner: containers-submodule` OR `runner=containers-submodule`, with optional
surrounding quotes/whitespace (matches both JSON and key=value evidence forms).

## Overrides

| Variable | Effect |
|---|---|
| `LAVA_EMULATOR_EVIDENCE_FILES` | Newline-separated explicit file list to scan (hermetic-test injection; bypasses git-new-file detection). |
| `LAVA_EMULATOR_RUNNER_STRICT=0` | Advisory mode — exit 0 even on violation. |

## Usage

```bash
bash scripts/check-emulator-runner-tag.sh                 # going-forward, strict (default)
LAVA_EMULATOR_RUNNER_STRICT=0 bash scripts/check-emulator-runner-tag.sh   # advisory
```

Invoked automatically as §6.X(6) from `scripts/check-constitution.sh`.

## Hermetic test

`tests/check-constitution/test_emulator_runner_tag.sh` — the §6.A paired
falsifiability proof: a fixture with a non-containers/absent runner tag FAILS
(exit 1); the same fixture with `runner: containers-submodule` PASSES (exit 0);
plus equals-form, non-emulator no-false-positive, advisory-mode, and real-tree
cases. Run:

```bash
bash tests/check-constitution/test_emulator_runner_tag.sh
```
