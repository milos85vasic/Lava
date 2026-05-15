# `scripts/check-challenge-coverage.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, 31st §6.L invocation)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + Lava §6.AE.6 (Comprehensive Challenge Coverage)

## Overview

Enforces §6.AE.1: every Lava feature module under `feature/*/` MUST have at least one Compose UI Challenge Test that targets it.

Targeting is detected by ANY of:
1. Import-based: a Challenge file contains `import lava.${feature}` or `import lava.${feature}.*`
2. Explicit marker: a Challenge file contains `// covers-feature: <feature-name>` comment
3. Filename convention: a Challenge file matches `Challenge*${Feature}*Test.kt`
4. Heuristic backstop: per-feature keyword mapping for known broad-flow coverage (Onboarding, Menu, Login, Search, Topic, ProviderRow, AppLaunch, etc.)

## Default mode

ADVISORY (`LAVA_CHALLENGE_COVERAGE_STRICT=0`) until §6.AE-debt closes (per-feature backfill of dedicated Challenges for any module currently relying only on broad-flow detection).

Set `LAVA_CHALLENGE_COVERAGE_STRICT=1` to fail on uncovered features.

## Usage

```bash
bash scripts/check-challenge-coverage.sh
LAVA_CHALLENGE_COVERAGE_STRICT=1 bash scripts/check-challenge-coverage.sh   # strict
```

## Inputs

None (walks `feature/*/` directories + `app/src/androidTest/kotlin/lava/app/challenges/Challenge*Test.kt` files).

## Outputs

- Per-feature coverage summary
- List of uncovered features (if any)
- Remediation hint per uncovered feature

## Side-effects

None — read-only scan.

## Cross-references

- `scripts/check-challenge-discrimination.sh` (sister scanner — §6.AB markers)
- `scripts/run-challenge-matrix.sh` (matrix runner)
- Lava `CLAUDE.md` §6.AE
