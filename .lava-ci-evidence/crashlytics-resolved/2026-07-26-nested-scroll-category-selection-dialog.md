# §6.O Closure Log — Nested-scroll FATAL in CategorySelectionDialog (Crashlytics c7c8ccc)

**State:** RESOLVED — FIX LANDED + REGRESSION COVERAGE LANDED (this cycle closes the owed
reproduce-first test debt recorded in
`2026-07-04-nested-scroll-c7c8ccc-INVESTIGATION.md` step 3 and in LVA-015).

## Crashlytics issue
- **ID:** `c7c8cccad09f72bd7bb95455226109b8` (variant `99c63e8de6532cdc8c4300b0f48fc4ce`)
- **Type:** FATAL — `java.lang.IllegalStateException: Vertically scrollable component was
  measured with an infinity maximum height constraints`
- **App:** `digital.vasic.lava.client` (release)
- **Impact:** firstSeen 1.2.3 → lastSeen 1.3.12. Confirmed sample event 2026-06-28T10:14:43Z
  on HUAWEI TXZ-W09 (Android 12, landscape) — see the 2026-07-04 INVESTIGATION note for the
  full forensic narrowing (credentials red-herring ruled out, 3 candidate screens ruled out).

## Root cause (confirmed, file:line)
`feature/search_result/src/main/kotlin/lava/search/result/categories/CategorySelectionDialog.kt:45`
(pre-fix) — the dialog content wrapped `PagesScreen` in a plain
`Column(modifier = Modifier.clip(AppTheme.shapes.large))` with **no height bound**, and the
`PagesScreen` child carried **no `modifier =`**. A plain Column measures its non-weighted
child with `maxHeight = Infinity`; PagesScreen's inner Scaffold/HorizontalPager propagated
that infinity to the `CategorySelectionList` / `CategorySelectionScreen` LazyLists (which use
`fillMaxHeight()` / `fillMaxSize()`), throwing the infinite-max-height `IllegalStateException`
the moment a user opened the Categories filter dialog from the search-result screen
(§6.Q antipattern, third site — sites #1/#2:
`2026-05-05-tracker-settings-nested-scroll.md`,
`2026-06-24-lazycolumn-nested-scroll-2nd-site.md`).

## Fix (landed 2026-07-04)
- **Fix commit:** `f1a2c3627e443dd4d2c16da59e7fe3a3bd71b9d7`
- `CategorySelectionDialog.kt:54-60` — `Modifier.fillMaxHeight(0.9f)` on the content Column
  (bounds the dialog height) **plus** `modifier = Modifier.weight(1f)` on the PagesScreen
  child (the Column measures the pager with finite constraints). Both halves are required.

## Regression coverage (landed this cycle — LVA-015)

### (a) Structural JVM test
`feature/search_result/src/test/kotlin/lava/search/result/categories/CategorySelectionDialogBoundedHeightRegressionTest.kt`
— pattern-guard idiom (same as `OnboardingInsetRegressionTest` /
`LavaIconsAppIconColorRegressionTest`): asserts the `fillMaxHeight(0.9f)` bound on the Column,
the `Modifier.weight(1f)` on PagesScreen, and the absence of the exact pre-fix
`Column(modifier = Modifier.clip(AppTheme.shapes.large))` shape.

### (b) §6.Q scanner CHECK 3
`tests/compose-layout/test_no_nested_scroll_antipattern.sh` gained **CHECK 3**: extracts every
`Dialog(` block (brace-balanced, word-boundary so `FooDialog(` call sites are excluded), strips
comment lines before analysis (same convention as CHECK 1 — a comment citing `fillMaxHeight`
must not masquerade as a real bound), and flags any Dialog block that hosts a lazy layout
(`LazyList(` / `LazyColumn(` / `PagesScreen(`) with NO height-bound token
(`fillMaxHeight` / `fillMaxSize` / `heightIn` / `.height(` / `.weight(`) anywhere in the block.
Allowlist marker: `// §6.Q-allow-dialog-unbounded:`.

### (c) Compose UI Challenge Test
`app/src/androidTest/kotlin/lava/app/challenges/Challenge71CategorySelectionDialogBoundedHeightTest.kt`
(C71) — drives the verbatim user path: MainActivity → Search → type query → IME submit →
SearchResultScreen → "Expand filters" → tap the Categories row ("Any") → assert the dialog
renders its "Current" tab and bottom-bar Apply/Cancel actions. MockWebServer is the only faked
boundary (identical seam to C58). Carries the §6.AB.3 FALSIFIABILITY REHEARSAL block (Mutation A:
crash-class revert; Mutation B: non-crashing bottomBar removal). SOURCE-WRITTEN; device-exec
on the §6.AE Containers matrix is PENDING per §6.AH (no live-device fallback per §6.AG).

## Validation (mechanical gates, reproduce-first RED→GREEN)

Mutation applied to `CategorySelectionDialog.kt`: reverted the content Column to
`Column(modifier = Modifier.clip(AppTheme.shapes.large))` and deleted the
`modifier = Modifier.weight(1f),` line from the PagesScreen call (the exact pre-fix shape).
Reverted: **yes** (restored via the committed fix; the file is byte-identical to HEAD
afterwards).

### RED — §6.Q scanner with the mutation applied

```
FAIL §6.Q-3: Compose Layout Antipattern Guard — Dialog content hosting an unbounded lazy layout:
  feature/search_result/src/main/kotlin/lava/search/result/categories/CategorySelectionDialog.kt:42: Dialog content hosts a lazy layout (LazyList/LazyColumn/PagesScreen) with NO height bound (fillMaxHeight/heightIn/weight) in the Dialog block — infinite-height lazy measure risk

Fix: bound the Dialog content height — e.g. Modifier.fillMaxHeight(0.9f) on the
     content Column AND Modifier.weight(1f) on the lazy child (or
     Modifier.heightIn(max = X.dp) on the lazy layout itself) — so the lazy
     layout is measured with finite constraints. If the layout is already
     bounded by a documented mechanism, add:
     // §6.Q-allow-dialog-unbounded: <reason>

Forensic anchor: Crashlytics c7c8cccad09f72bd7bb95455226109b8 — see
.lava-ci-evidence/crashlytics-resolved/2026-07-26-nested-scroll-category-selection-dialog.md
SCANNER_EXIT=1
```

### RED — structural JVM test with the mutation applied

```
> Task :feature:search_result:testDebugUnitTest

CategorySelectionDialogBoundedHeightRegressionTest > dialogContentColumn_boundsHeight FAILED
    java.lang.AssertionError at CategorySelectionDialogBoundedHeightRegressionTest.kt:55

CategorySelectionDialogBoundedHeightRegressionTest > pagesScreenChild_isWeighted FAILED
    java.lang.AssertionError at CategorySelectionDialogBoundedHeightRegressionTest.kt:67

CategorySelectionDialogBoundedHeightRegressionTest > dialogContentColumn_isNotThePreFixUnboundedShape FAILED
    java.lang.AssertionError at CategorySelectionDialogBoundedHeightRegressionTest.kt:79

3 tests completed, 3 failed

> Task :feature:search_result:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':feature:search_result:testDebugUnitTest'.
> There were failing tests. See the report at: file:///run/media/milosvasic/DATA4TB/Projects/lava/feature/search_result/build/reports/tests/testDebugUnitTest/index.html

BUILD FAILED in 1m 12s
261 actionable tasks: 17 executed, 244 up-to-date
```

### GREEN — both gates after restoring the fix

Scanner:

```
[compose-layout] OK: no nested-scroll antipattern detected in feature/ + core/ + app/src/main/
SCANNER_EXIT=0
```

Structural JVM test:

```
> Task :feature:search_result:testDebugUnitTest

BUILD SUCCESSFUL in 42s
261 actionable tasks: 10 executed, 251 up-to-date
GRADLE_EXIT=0
```

Additional verification this cycle: `:app:compileDebugAndroidTestKotlin` (proves C71
compiles) and `scripts/check-challenge-discrimination.sh` (72 Challenges, 0 violations).

Bluff-Audit:
```
Test: CategorySelectionDialogBoundedHeightRegressionTest + §6.Q scanner CHECK 3
Mutation: reverted CategorySelectionDialog.kt to the pre-fix shape
  (Column(modifier = Modifier.clip(AppTheme.shapes.large)) + PagesScreen without modifier =)
Observed-Failure: FAIL §6.Q-3 … CategorySelectionDialog.kt:42: Dialog content hosts a lazy
  layout … with NO height bound; JVM AssertionErrors on dialogContentColumn_boundsHeight /
  pagesScreenChild_isWeighted / dialogContentColumn_isNotThePreFixUnboundedShape
Reverted: yes
```

## §6.O.5
Operator close-marks the Crashlytics issue `c7c8cccad09f72bd7bb95455226109b8` in the Console
after on-device verification of the build shipping this coverage, and executes C71 on the
§6.AE Containers matrix to fill its attestation row.
