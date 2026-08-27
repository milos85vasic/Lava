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

## Two required conditions, not one (tightened 2026-08-26)

A file "records emulator execution" only when **both** hold:

1. **MARKER** — the file contains at least one emulator-run marker.
2. **ATTESTATION SHAPE** — the file *records* a run rather than *discussing* one, via
   either:
   - **(2a)** an attestation FIELD in key position — one of the §6.I.4 per-row field
     names (`"avd":`, `avd=`, `| avd |` and siblings) in JSON-key, key=value or
     markdown-table-cell form; or
   - **(2b)** a marker sharing a line with an INVOCATION or OUTCOME token — `gradlew`,
     `BUILD SUCCESSFUL`/`FAILED`, `am instrument`, `--tests`, `adb -s`/`adb shell`,
     `avdmanager`, `emulator-matrix -<flag>` — which is what a plaintext run log
     looks like.

### Why the mention test was not enough

The previous version matched condition (1) alone, and asserted **in its own comment**
that bluff-hunt logs "that merely quote a marker in prose" would stay clear because they
are "unlikely to be NEW evidence files added in the same change".

That assumption was stated and never tested, and it was wrong. A §6.N bluff-hunt record
was flagged whose only marker sits inside an `"unconfirmed"` prose field whose own text
reads *"Gradle was not run (resource constraint)"* — so the gate was calling a written
record of **not** running an emulator an unattested emulator run.

The tightening narrows by **what the file is**, never by where it lives: no path
exemption was added, and a test writes a genuine untagged attestation *into*
`.lava-ci-evidence/bluff-hunt/` and asserts it is still flagged. Measured across all 117
tracked marker-bearing files, exactly 4 reclassify as prose; each was read and confirmed.
