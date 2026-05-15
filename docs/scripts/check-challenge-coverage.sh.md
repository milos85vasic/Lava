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

STRICT (`LAVA_CHALLENGE_COVERAGE_STRICT=1`) — flipped 2026-05-15 after the per-feature backfill drained the queue (18 covered + 1 exempted + 0 uncovered).

Set `LAVA_CHALLENGE_COVERAGE_STRICT=0` to revert to advisory mode (e.g. during a heavy refactor that adds new features in bulk).

## Exemption mechanism

Pre-wired-but-not-yet-user-reachable features can be exempted via the ledger at `.lava-ci-evidence/challenge-coverage-exemptions.md` using `// AE-exempt: <feature-name>` markers. Each exemption MUST cite WHAT/WHY/WHEN/UNBLOCK per §6.AE.1.

## Hermetic test

`tests/check-constitution/test_challenge_coverage.sh` exercises 4 falsifiability fixtures: uncovered-rejected, covered-via-import-accepted, exempted-via-marker-accepted, advisory-mode-does-not-fail. All PASS. The scanner respects `LAVA_REPO_ROOT` env so the test can use synthetic `feature/*` fixtures without touching the real repo.

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
