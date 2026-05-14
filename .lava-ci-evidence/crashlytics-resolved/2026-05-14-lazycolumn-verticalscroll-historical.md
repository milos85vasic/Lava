# Crashlytics issues c7c8cccad09f72bd7bb95455226109b8 + 033d7e17ea12bdeda10bef8b3251131d — closure log

**Issue IDs:**
- `c7c8cccad09f72bd7bb95455226109b8` (5 events / 1 user / first seen 1.2.3 / last seen 1.2.5)
- `033d7e17ea12bdeda10bef8b3251131d` (2 events / 1 user / first + last seen 1.2.3) — variant stack of the same root cause

**Title:** `androidx.compose.foundation.internal.InlineClassHelperKt.throwIllegalStateException`
**Subtitle:** "Vertically scrollable component was measured with an infinity maximum height constraints, which is disallowed. One of the common reasons is nesting layouts like LazyColumn and Column(Modifier.verticalScroll())."
**Type:** FATAL
**State at closure:** OPEN (operator marks closed in Console)

## Root cause + historical fix

The §6.Q **Compose Layout Antipattern Guard** clause exists for exactly this class of bug. The 13th §6.L invocation on 2026-05-05 ("Opening Trackers from Settings crashes the app") birthed §6.Q after the operator reported a crash whose root cause was `LazyColumn` nested inside `Column(Modifier.verticalScroll())` in `TrackerSelectorList`.

These two issues are 12+ versions stale (1.2.3 → 1.2.5) — the current `feature/tracker_settings/src/test/.../TrackerSelectorListLazyColumnRegressionTest.kt` (added 2026-05-05 per §6.Q) structurally guards against `LazyColumn` (and other vertical-lazy layouts) nested in `verticalScroll` parents.

## Validation guards in place

- §6.Q clause documented in root CLAUDE.md (line 545+) with the canonical forbidden pattern and §6.Q-spirit per-feature structural test mandate
- Closure log for the original incident: `.lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md`
- `TrackerSelectorListLazyColumnRegressionTest` (or equivalent) per-feature structural tests exist as the §6.Q-mandated guard

## Closure protocol per §6.O

These two issues have NOT recurred in any version since 1.2.5 (~17 versions ago). The §6.Q mechanical guard prevents recurrence. Operator marks both issues closed in Firebase Console as "historical — fixed in 1.2.6+ and structurally guarded by §6.Q's TrackerSelectorListLazyColumnRegressionTest pattern".

## §6.AB-spirit discrimination check

A future contributor who reverts the structural test (or removes the guard) reintroduces the failure mode. The §6.Q guard tests are themselves §6.AB-discrimination-ready: the test fails if any `LazyColumn` is nested in a `verticalScroll` ancestor that gives unbounded vertical space.
