# `scripts/autonomous-qa/aggregate-evidence.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.J / §6.AK (anti-bluff distribute gate)
**Classification:** project-specific

## Overview

Rolls up the autonomous-QA per-iteration verdicts (written by
`run-iteration.sh`) into:

1. A cycle-level **`CYCLE-SUMMARY.md`** (both backends, per-backend + overall
   totals) under `.lava-ci-evidence/autonomous-qa/<date>/`.
2. The **§6.AK distribute-gate artifacts** that
   `scripts/check-cycle-coverage.sh` consumes, written to
   `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/` (the
   same `--evidence-dir` `firebase-distribute.sh` pins for the client app;
   both §6.AA stages share it):
   - `cycle-coverage-map-<VER>.yaml` — top-level `version:` + `fixes:` list
   - `<VER>-test-evidence.json` — `version` / `channel` / `commit_sha` /
     `timestamp` + `test_results[]`

Plan: `docs/superpowers/plans/2026-06-29-autonomous-qa-backend-provider-matrix.md`.

## Usage

```bash
scripts/autonomous-qa/aggregate-evidence.sh \
  --date <YYYY-MM-DD> --version <v> --channel <debug|release> \
  --timestamp <ISO8601> [--evidence-dir <dir>]
```

`--date`, `--version`, `--channel`, `--timestamp` are required; `--channel`
must be exactly `debug` or `release`. `--evidence-dir` overrides the
distribute-changelog evidence directory (used by tests).

## Anti-bluff properties (§6.J / §6.AK)

- Every number is derived from the actual `verdict.json` files on disk. A
  PASS/total is NEVER emitted that is not present in the data.
- A **missing** `verdict.json` is counted FAIL and noted (`verdict.json
  MISSING — counted FAIL`); a **corrupt** one (no/invalid verdict field)
  likewise — never silently skipped.
- Unparseable numeric fields default to 0 (never invented).
- **Only iterations whose verdict is PASS** become asserted claims in the
  cycle-coverage-map — a claim asserts the feature works; asserting a FAILed
  iteration would be the exact bluff §6.AK forbids. A cycle with zero PASS
  verdicts emits zero claims, explicitly.
- `test_results[]` records ALL iterations (PASS/SKIP/FAIL) — the full honest
  device-gate record — each tagged with device `CZ_API34_Phone-containerized`
  and the covering Challenge
  `lava.app.challenges.Challenge70AutonomousQaProviderMatrixTest
  [<backend>/<providers>/<query>]`, with `duration_seconds` recovered from the
  JUnit XML (`null` when absent).

## Outputs

| File | Content |
|---|---|
| `.lava-ci-evidence/autonomous-qa/<date>/CYCLE-SUMMARY.md` | Full per-iteration table (backend/providers/query/verdict/counts/note), per-backend totals, overall totals |
| `<evidence-dir>/cycle-coverage-map-<VER>.yaml` | PASS-only claims, each mapped to the covering Challenge |
| `<evidence-dir>/<VER>-test-evidence.json` | version/channel/commit_sha/timestamp + full `test_results[]` |

## Exit codes

- `0` — aggregation completed (a WARN is printed when no iterations exist
  under the date dir; the artifacts are still written, honestly empty)
- `2` — usage error (missing/invalid flags)

## Companion files

- `scripts/autonomous-qa/run-iteration.sh` — the verdict producer
- `scripts/check-cycle-coverage.sh` (+ its doc) — the §6.AK gate that consumes the artifacts
- `scripts/firebase-distribute.sh` (+ its doc) — pins `--evidence-dir` for the client channel
