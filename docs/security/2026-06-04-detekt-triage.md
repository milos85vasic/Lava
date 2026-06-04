# Detekt Static-Analysis Triage — 2026-06-04

First-ever Detekt run on the Lava Kotlin tree. Detekt is wired through the
`StaticAnalysisConventionPlugin` buildSrc convention (applied by
`lava.kotlin.library`, `lava.android.library`, `lava.android.application`, and
transitively `lava.android.feature`), so **every module** is analysed with zero
per-module configuration.

- **Plugin:** `io.gitlab.arturbosch.detekt` 1.23.8 (Kotlin 2.1 compatible).
- **Config:** `config/detekt/detekt.yml` (detekt's curated default + `buildUponDefaultConfig = true`; one tuning — `FunctionNaming.ignoreAnnotated: ['Composable']` so PascalCase `@Composable` functions are not flagged, since Lava is 100% Jetpack Compose).
- **Baseline:** one `detekt-baseline.xml` **per module** (detekt's idiomatic multi-module pattern — a single shared baseline is impossible because each module's `detektBaseline` task *rewrites*, not merges, its target file). 45 baseline files, **674 deduplicated finding IDs** captured.
- **Severity:** detekt's defaults are uniformly `warning`; the gate fails on any finding via `build.maxIssues: 0`. The baseline suppresses today's findings so the gate is **green going forward** while any NEW finding fails the build.

## Run commands

```bash
# Surface findings (first run, no baseline):
nice -n 19 ./gradlew --no-daemon --max-workers=2 --build-cache detekt
# Capture baseline (serial not required for per-module files):
nice -n 19 ./gradlew --no-daemon --max-workers=2 --continue detektBaseline
# Confirm green:
nice -n 19 ./gradlew --no-daemon --max-workers=2 --build-cache detekt   # BUILD SUCCESSFUL
```

## Findings by rule (raw console findings: **849**)

> The 849 raw console findings collapse to **674** baseline `<ID>`s because detekt
> deduplicates structurally-identical findings (same rule + signature) per module.

| Rule | Count |
|---|---|
| `MagicNumber` | 383 |
| `EmptyFunctionBlock` | 76 |
| `LongParameterList` | 63 |
| `MaxLineLength` | 53 |
| `LongMethod` | 45 |
| `TooManyFunctions` | 42 |
| `TopLevelPropertyNaming` | 34 |
| `UnusedPrivateMember` | 33 |
| `TooGenericExceptionCaught` | 31 |
| `ReturnCount` | 24 |
| `CyclomaticComplexMethod` | 21 |
| `SwallowedException` | 9 |
| `UseCheckOrError` | 6 |
| `ThrowsCount` | 4 |
| `ComplexCondition` | 4 |
| `UnusedPrivateProperty` | 3 |
| `MatchingDeclarationName` | 3 |
| `ForbiddenComment` | 3 |
| `PrintStackTrace` | 2 |
| `NestedBlockDepth` | 2 |
| `FunctionOnlyReturningConstant` | 2 |
| `ThrowingExceptionsWithoutMessageOrCause` | 1 |
| `SpreadOperator` | 1 |
| `InvalidPackageDeclaration` | 1 |
| `ImplicitDefaultLocale` | 1 |
| `DestructuringDeclarationWithTooManyEntries` | 1 |
| `ConstructorParameterNaming` | 1 |

## What was FIXED vs BASELINED

**Fixed (source code): 0.** This change is scoped (by the worktree mandate) to
`buildSrc/`, `gradle/libs.versions.toml`, and `config/detekt/` — it MUST NOT touch
`.kt` source files. Every finding is therefore **baselined**, and the genuine
correctness/potential-bug findings are recorded below as backlog for a later
source-touching phase (per the §6.D / §6.T.4 discipline: fixes land with a
reproducing test, not in a build-wiring commit).

One **config-level** correction was applied (not a source fix): the Compose
`FunctionNaming` false-positive class (10 findings on `@Composable` PascalCase
functions in `api-app`) is suppressed by `ignoreAnnotated: ['Composable']` rather
than baselined — these are not findings at all, they are the Compose convention.

**Baselined: all 674 deduplicated IDs.** Breakdown by class:

- **Style / complexity (deferred, not bugs):** `MagicNumber` (383), `MaxLineLength`
  (53), `LongMethod` (45), `LongParameterList` (63), `TooManyFunctions` (42),
  `TopLevelPropertyNaming` (34), `ReturnCount` (24), `CyclomaticComplexMethod`
  (21), `ThrowsCount` (4), `ComplexCondition` (4), `NestedBlockDepth` (2),
  `MatchingDeclarationName` (3), `FunctionOnlyReturningConstant` (2),
  `ForbiddenComment` (3), `SpreadOperator` (1), `InvalidPackageDeclaration` (1),
  `ConstructorParameterNaming` (1), `DestructuringDeclarationWithTooManyEntries` (1).
- **Intentional empty bodies:** `EmptyFunctionBlock` (76) — the large majority are
  mDNS / NSD listener interface overrides (`NsdMdnsAdvertiser`) and test no-op
  callbacks where an empty body is the correct implementation. Removing them would
  break the override contract, so they are baselined, not "fixed".
- **Dead code (review needed):** `UnusedPrivateMember` (33), `UnusedPrivateProperty`
  (3) — removal is behavior-safe but must be reviewed per-site (some are Compose
  preview helpers / reflection targets); deferred.
- **Correctness / potential bugs (top backlog — see below):** `TooGenericExceptionCaught`
  (31), `SwallowedException` (9), `UseCheckOrError` (6), `PrintStackTrace` (2),
  `ImplicitDefaultLocale` (1), `ThrowingExceptionsWithoutMessageOrCause` (1).

## Top backlog — correctness/potential-bug findings (fix in a later source phase)

These are the highest-value findings. Each MUST be fixed with a reproducing test
per §6.T.1 / §6.D when a source-touching phase opens.

1. **`ImplicitDefaultLocale` — `core/tracker/rutracker/.../domain/Utils.kt:102`**
   (HIGH). `String.format("%.1f %sB", …)` uses the implicit default locale to format
   file sizes. On a locale whose decimal separator is a comma (e.g. `tr`, `de`,
   `ru` — and Lava targets Russian-tracker users), `"%.1f"` emits `"1,5"` instead
   of `"1.5"`, corrupting displayed sizes and any downstream parse. Real
   user-visible latent bug. Fix: pass `Locale.US` (or `Locale.ROOT`).

2. **`PrintStackTrace` ×2 — `RuTrackerNetworkApi.kt:87`, `RuTrackerAuth.kt:66`**
   (MEDIUM). Debug `printStackTrace()` calls in production network/auth paths. Per
   §6.AC these MUST route through the analytics non-fatal channel
   (`AnalyticsTracker.recordNonFatal`), not stderr. Behavior-changing; deferred to a
   §6.AC telemetry pass.

3. **`SwallowedException` ×9 + `TooGenericExceptionCaught` ×31** (MEDIUM). Broad
   `catch (e: Exception)` / swallowed exceptions across tracker + network code. Each
   site needs review against §6.AC (record non-fatal) — exactly the silent-fallback
   class §6.AC exists to surface. Highest-count correctness backlog.

4. **`UseCheckOrError` ×6** (LOW). `throw IllegalStateException(...)` that should be
   `check()` / `error()` — idiomatic, behavior-equivalent, low-risk; e.g.
   `core/network/impl/SwitchingNetworkApi.kt:179,227`,
   `core/tracker/client/LavaTrackerSdk.kt:540`,
   `core/tracker/gutenberg/.../GutenbergSearch.kt:43` + `GutenbergBrowse.kt:66`.

5. **`ThrowingExceptionsWithoutMessageOrCause` ×1 — `core/testing/.../TestAuthService.kt:11`**
   (LOW, test fake). Default-constructor exception in a `core:testing` fake; per the
   Anti-Bluff Pact Third Law, fakes must mirror real behavior — add a message.

## Maintenance notes

- To re-baseline after legitimately fixing findings:
  `./gradlew --no-daemon detektBaseline` then commit the shrunk `detekt-baseline.xml`.
- New findings in new code fail the build (not in baseline) — the gate is anti-bluff
  by construction: it only passes because today's findings are explicitly recorded,
  not because the rule set is empty.
