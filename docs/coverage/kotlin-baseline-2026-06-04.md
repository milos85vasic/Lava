# Kotlin Coverage Baseline — 2026-06-04

Phase-0 / §6.D behavioral-coverage baseline for the Kotlin (Android client +
`api-app`) tree, captured by **Kover 0.9.1** wired through the
`StaticAnalysisConventionPlugin` buildSrc convention (applied to every module).
Later completeness-program phases drive these numbers up; this file is the
starting point against which progress is measured.

## How it was produced

```bash
nice -n 19 ./gradlew --no-daemon --max-workers=2 --continue \
  koverXmlReport koverHtmlReport \
  -x :feature:credentials:testDebugUnitTest \
  -x :feature:credentials:testReleaseUnitTest
```

- Real test execution → real coverage (Kover instruments the actual unit-test run).
- The two excluded `:feature:credentials` test tasks carry the **pre-existing
  flaky** test `CredentialsViewModelTest > select provider updates selectedProvider`
  (`TurbineAssertionError: No value produced in 3s`) — the same timing flake the
  constitution's §6.L 68th-cycle note records as already-known. It is unrelated to
  this build-wiring change (which touches only `buildSrc/`, `gradle/`, `config/detekt/`).
  Excluding it lets the full tree's coverage report complete; `core/credentials`
  coverage below reflects its non-flaky tests that still ran.
- Raw per-module Kover XML reports archived at
  `.lava-ci-evidence/completeness-program/coverage/kotlin-baseline-2026-06-04/`.

## Headline (aggregate across all 55 modules)

| Counter | Coverage | Covered / Total |
|---|---|---|
| **LINE** | **18.65%** | 4709 / 25244 |
| BRANCH | 20.01% | 1692 / 8457 |
| INSTRUCTION | 15.91% | 35470 / 222985 |
| METHOD | 17.14% | 1189 / 6939 |
| CLASS | 16.91% | 508 / 3004 |

> No root Kover aggregation project exists (there is no root `build.gradle.kts`,
> per the convention-plugin architecture), so these aggregate figures are summed
> from the 55 per-module `report.xml` counters. Per-module `koverHtmlReport`
> output lives under each module's `build/reports/kover/html/`.

## Per-module line + branch coverage

| Module | Line % | Line cov/total | Branch % | Branch cov/total |
|---|---|---|---|---|
| `core/tracker/gutenberg` | 95.41% | 208/218 | 51.33% | 77/150 |
| `core/tracker/archiveorg` | 90.91% | 190/209 | 47.31% | 88/186 |
| `core/tracker/mirror` | 88.89% | 8/9 | 50.00% | 2/4 |
| `core/tracker/rutor` | 83.50% | 496/594 | 56.84% | 245/431 |
| `core/apiengine` | 83.46% | 106/127 | 36.36% | 32/88 |
| `core/tracker/kinozal` | 83.16% | 237/285 | 41.18% | 56/136 |
| `core/tracker/nnmclub` | 82.34% | 289/351 | 47.06% | 104/221 |
| `core/tracker/registry` | 75.00% | 3/4 | 0.00% | 0/2 |
| `core/sync` | 65.22% | 15/23 | 0.00% | 0/0 |
| `core/credentials` | 58.46% | 235/402 | 59.68% | 74/124 |
| `core/tracker/client` | 49.82% | 410/823 | 52.38% | 110/210 |
| `core/tracker/rutracker` | 43.53% | 632/1452 | 30.14% | 211/700 |
| `core/auth/impl` | 40.62% | 26/64 | 25.00% | 6/24 |
| `core/network/impl` | 37.43% | 189/505 | 42.86% | 84/196 |
| `core/tracker/api` | 37.21% | 48/129 | 15.60% | 34/218 |
| `feature/onboarding` | 30.22% | 243/804 | 30.75% | 103/335 |
| `feature/login` | 28.76% | 266/925 | 30.28% | 86/284 |
| `core/tracker/testing` | 28.57% | 24/84 | 8.33% | 3/36 |
| `core/testing` | 26.77% | 34/127 | 75.00% | 6/8 |
| `api-app` | 19.50% | 118/605 | 19.28% | 32/166 |
| `feature/credentials` | 19.32% | 96/497 | 16.55% | 24/145 |
| `feature/menu` | 18.27% | 129/706 | 15.48% | 39/252 |
| `feature/connection` | 16.07% | 58/361 | 20.00% | 22/110 |
| `feature/provider_config` | 15.00% | 81/540 | 8.99% | 16/178 |
| `core/preferences` | 13.85% | 27/195 | 22.67% | 17/75 |
| `core/network/api` | 13.71% | 41/299 | 4.39% | 15/342 |
| `feature/search_result` | 11.04% | 146/1323 | 8.02% | 49/611 |
| `app` | 9.45% | 52/550 | 24.10% | 40/166 |
| `core/models` | 9.22% | 19/206 | 71.43% | 30/42 |
| `core/data` | 6.84% | 62/906 | 10.30% | 41/398 |
| `core/domain` | 6.75% | 86/1274 | 15.00% | 24/160 |
| `core/designsystem` | 5.62% | 135/2400 | 0.85% | 4/472 |
| `core/auth/api` | 0.00% | 0/4 | 0.00% | 0/0 |
| `core/common` | 0.00% | 0/8 | 0.00% | 0/0 |
| `core/database` | 0.00% | 0/3187 | 0.00% | 0/428 |
| `core/dispatchers` | 0.00% | 0/11 | 0.00% | 0/0 |
| `core/downloads` | 0.00% | 0/97 | 0.00% | 0/59 |
| `core/logger` | 0.00% | 0/23 | 0.00% | 0/0 |
| `core/navigation` | 0.00% | 0/309 | 1.09% | 1/92 |
| `core/notifications` | 0.00% | 0/77 | 0.00% | 0/6 |
| `core/ui` | 0.00% | 0/862 | 0.33% | 1/307 |
| `core/work/api` | 0.00% | 0/0 | 0.00% | 0/0 |
| `core/work/impl` | 0.00% | 0/270 | 0.00% | 0/48 |
| `feature/account` | 0.00% | 0/115 | 3.85% | 1/26 |
| `feature/bookmarks` | 0.00% | 0/159 | 0.00% | 0/38 |
| `feature/category` | 0.00% | 0/297 | 3.77% | 4/106 |
| `feature/credentials_manager` | 0.00% | 0/392 | 0.75% | 1/134 |
| `feature/favorites` | 0.00% | 0/124 | 2.50% | 1/40 |
| `feature/forum` | 0.00% | 0/229 | 0.00% | 0/46 |
| `feature/main` | 0.00% | 0/42 | 0.00% | 0/28 |
| `feature/rating` | 0.00% | 0/156 | 2.22% | 1/45 |
| `feature/search` | 0.00% | 0/425 | 0.71% | 1/140 |
| `feature/search_input` | 0.00% | 0/362 | 0.00% | 0/148 |
| `feature/topic` | 0.00% | 0/1015 | 1.88% | 5/266 |
| `feature/visited` | 0.00% | 0/83 | 6.67% | 2/30 |

## Reading the baseline

- **Best-covered:** the tracker SDK plugin modules — `core/tracker/gutenberg`
  (95.41%), `core/tracker/archiveorg` (90.91%), `core/tracker/rutor` (83.50%),
  `core/tracker/kinozal` (83.16%), `core/tracker/nnmclub` (82.34%), and
  `core/apiengine` (83.46%). These are the §6.D behaviorally-tested surfaces.
- **0% modules** are dominated by (a) generated code with no behavioral tests
  (`core/database` = 3187 lines of Room-generated DAOs/schema, `core/ui`,
  `core/navigation`) and (b) Compose feature screens whose only coverage path is
  the androidTest Challenge suite (NOT counted here — Kover's `koverXmlReport`
  measures JVM unit tests only; instrumentation coverage is a separate, later
  measurement). `feature/topic`, `feature/search`, `feature/category` etc. show
  0% because their behavior is exercised by Compose UI Challenge Tests on the
  emulator matrix, not by JVM unit tests.
- **Drivers of the low aggregate:** `core/designsystem` (2400 lines, 5.62%),
  `core/database` (3187 lines, 0%), `feature/search_result` (1323 lines, 11%),
  `core/domain` (1274 lines, 6.75%), and `feature/topic` (1015 lines, 0%) are the
  largest uncovered surfaces and the highest-leverage targets for later phases.

## Phase targets (informational — set by later completeness-program phases)

The completeness program drives LINE coverage up from this 18.65% baseline. The
highest-leverage, lowest-risk targets (large + behaviorally-testable at the JVM
unit level, not Compose-only):

1. `core/domain` use cases (1274 lines, 6.75%) — pure Kotlin, ViewModel-reachable.
2. `core/data` repositories (906 lines, 6.84%) — fakes already exist in `core:testing`.
3. `core/network/impl` (505 lines, 37%) and `core/tracker/client` (823 lines, 50%).
4. `feature/*` ViewModels via `orbit-test` (wired in every feature, currently unused).
