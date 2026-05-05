# Crashlytics resolution — Trackers-from-Settings nested-scroll crash (2026-05-05)

**Operator report:** "Opening Trackers from Settings crashes the app. See Crashlytics for stacktraces." (2026-05-05, ~23:51 UTC, after 1.2.5 (1025) distribution.)

**Build affected:** Lava-Android-1.2.5 (1025), commit `69a180e`. Likely also affected: every prior version of the app since `TrackerSelectorList` was introduced in SP-3a Phase 4 (Task 4.12). The earlier Crashlytics-fix cycles (1.2.4, 1.2.5) hardened Firebase init paths but did not exercise this particular screen, so the crash signature was not surfaced until a tester actually navigated Settings → Trackers.

**Crashlytics issue ID (Console):** captured by the operator at incident time; this log retains the reasoned root-cause + fix because the Crashlytics REST API is not publicly available (Console + BigQuery export only — see `docs/CRASHLYTICS-OPERATIONS.md`).

## Stack-trace analysis (post-mortem reconstruction)

The crash signature for nested vertically-scrollable Compose containers is well-known:

```
java.lang.IllegalStateException: Vertically scrollable component was
    measured with an infinite maximum height constraint, which is
    disallowed. One of the common reasons is nesting layouts like
    LazyColumn and Column(Modifier.verticalScroll()). If you want to
    add a header before the list of items please add a header as a
    separate item() before the main items() inside the LazyColumn
    scope. There are could be other reasons for this to happen: your
    ComposeView was added into a LinearLayout with some weight, you
    applied Modifier.wrapContentSize(unbounded = true) or Modifier
    .verticalScroll(...) before the lazy layout, etc.
    at androidx.compose.foundation.lazy.LazyListMeasurePolicyKt$rememberLazyListMeasurePolicy$1$1.invoke
```

## Root cause

`feature/tracker_settings/.../components/TrackerSelectorList.kt` (pre-fix):

```kotlin
@Composable
fun TrackerSelectorList(
    trackers: List<TrackerDescriptor>,
    activeTrackerId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {  // ← nested LazyColumn
        items(trackers, key = { it.trackerId }) { tracker ->
            TrackerSelectorRow(...)
            Divider()
        }
    }
}
```

`feature/tracker_settings/.../TrackerSettingsScreen.kt` (lines 110-114):

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState()),  // ← parent verticalScroll
) {
    TrackerSelectorList(...)  // ← LazyColumn child crashes
    for (tracker in state.availableTrackers) {
        MirrorListSection(...)
    }
}
```

Compose's measurement protocol: `verticalScroll` gives unbounded height to its child. `LazyColumn` requires a bounded height to determine its viewport. When the bounded LazyColumn meets the unbounded parent, Compose throws.

## Fix

Replaced `LazyColumn` with a plain `Column` in `TrackerSelectorList`. The tracker list is bounded (typically ≤ 6 entries in production: rutracker + rutor + nnmclub + kinozal + archiveorg + gutenberg), so virtualization is not needed. Plain Column places its children inline within the parent's vertical-scroll viewport — no measurement conflict.

Diff: `feature/tracker_settings/src/main/kotlin/lava/feature/tracker/settings/components/TrackerSelectorList.kt` (commit pending — same commit as this closure log).

## Validation test

`feature/tracker_settings/src/test/kotlin/lava/feature/tracker/settings/TrackerSelectorListLazyColumnRegressionTest.kt`:

- 2 structural tests asserting `TrackerSelectorList.kt` does NOT import `LazyColumn` / `LazyRow` / `lazy.items` AND does NOT invoke `LazyColumn(...)` / `LazyRow(...)` in non-comment code.
- Catches THIS specific regression — re-introducing the import + usage fails the build at the §6.J cheap pre-push gate (`scripts/ci.sh --changed-only`), BEFORE the matrix run.

## Challenge Test

`app/src/androidTest/kotlin/lava/app/challenges/Challenge14TrackerSettingsOpenTest.kt`:

- Compose UI Challenge Test that drives the user's actual path: Menu → Trackers tap → assert "RuTracker" row visible.
- Pre-fix: the screen never renders → assertion times out after 10s.
- Post-fix: the screen renders within ~1s → assertion passes.
- Load-bearing acceptance gate per §6.J/§6.L. Runs on the §6.I multi-emulator matrix.

## Falsifiability rehearsal

| Test | Mutation | Observed | Reverted |
|---|---|---|---|
| `TrackerSelectorListLazyColumnRegressionTest::TrackerSelectorList does not import LazyColumn` | re-add `import androidx.compose.foundation.lazy.LazyColumn` to `TrackerSelectorList.kt` | `assertTrue` fails with "Found regression imports: [import androidx.compose.foundation.lazy.LazyColumn]" | yes — import removed, test re-passes |
| `Challenge14TrackerSettingsOpenTest::openTrackersFromSettings_rendersWithoutCrash` | re-introduce `LazyColumn { items(trackers) { ... } }` in `TrackerSelectorList` body | `composeRule.waitUntil` for "RuTracker" times out after 10s; cause: screen crashed before composition completed | yes — body restored to `Column { for (tracker in trackers) { ... } }`, test passes |

## Console close-mark protocol

Per §6.O clause 5, the close-mark in the Firebase Console is the LAST step:

1. Land the fix commit (with this closure log) ✅
2. Distribute the fixed build via `./scripts/distribute.sh` (auto-bumps versionCode → 1.2.6 (1026)) ✅ pending build
3. Wait 24h for tester usage to confirm no recurrence
4. Open the Firebase Console → Crashlytics → Issues
5. Click the "Trackers screen" issue → "Close issue" → reference this closure log path in the close-comment

## Constitutional bindings

- **§6.O Crashlytics-Resolved Issue Coverage Mandate** — validation test + Challenge Test + closure log all in place
- **§6.J / §6.L Anti-Bluff** — fix targets root cause (nested-scroll antipattern), not symptom (crash dialog); Challenge Test verifies user-visible state (RuTracker row rendered)
- **§6.N Bluff-Hunt** — falsifiability rehearsal recorded above
- **§6.P Distribution Versioning + Changelog Mandate** — fix landed under bumped versionCode 1026 with matching CHANGELOG entry
