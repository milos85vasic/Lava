package lava.search.result.categories

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6.Q structural regression test for the CategorySelectionDialog nested-scroll
 * FATAL — Crashlytics issue `c7c8cccad09f72bd7bb95455226109b8`
 * (`IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`, firstSeen 1.2.3 → lastSeen 1.3.12,
 * fixed 2026-07-04, fix commit f1a2c362).
 *
 * Root cause: the dialog content wrapped `PagesScreen` in a plain
 * `Column(modifier = Modifier.clip(AppTheme.shapes.large))` with NO height
 * bound, and `PagesScreen` itself carried no `modifier =`. A plain Column
 * measures its non-weighted child with maxHeight = Infinity; PagesScreen's
 * inner Scaffold/HorizontalPager propagated that infinity to the
 * CategorySelectionList / CategorySelectionScreen LazyLists (which use
 * `fillMaxHeight()` / `fillMaxSize()`), throwing the infinite-max-height
 * IllegalStateException the moment a user opened the Categories filter dialog
 * from the search-result screen.
 *
 * Fix guarded here:
 *   1. `Modifier.fillMaxHeight(0.9f)` on the content Column — bounds the
 *      dialog height.
 *   2. `modifier = Modifier.weight(1f)` on the PagesScreen child — the Column
 *      measures the pager with finite constraints.
 * Both halves are required: fillMaxHeight alone leaves PagesScreen unweighted
 * (the Column would measure it with its own bounded-but-child-driven height,
 * which the HorizontalPager still reports as unbounded), and weight alone
 * leaves the Column itself unbounded inside the Dialog window.
 *
 * Falsifiability rehearsal:
 *   1. Revert `CategorySelectionDialog.kt` to the pre-fix shape:
 *      `Column(modifier = Modifier.clip(AppTheme.shapes.large)) {` and delete
 *      the `modifier = Modifier.weight(1f),` line from the PagesScreen call.
 *   2. Re-run this test.
 *   3. Expected failures: `dialogContentColumn_boundsHeight` and
 *      `pagesScreenChild_isWeighted` AssertionErrors (plus
 *      `dialogContentColumn_isNotThePreFixUnboundedShape` on the exact revert).
 *   4. Restore; re-run; passes.
 *
 * The rendered-UI Challenge driving this same path on a real device is
 * `lava.app.challenges.Challenge71CategorySelectionDialogBoundedHeightTest`.
 */
class CategorySelectionDialogBoundedHeightRegressionTest {

    private val source =
        File("src/main/kotlin/lava/search/result/categories/CategorySelectionDialog.kt").readText()

    @Test
    fun dialogContentColumn_boundsHeight() {
        assertTrue(
            "CategorySelectionDialog.kt content Column MUST carry " +
                "Modifier.fillMaxHeight(0.9f) — a plain Column inside a Dialog " +
                "measures its non-weighted child with maxHeight = Infinity, which " +
                "PagesScreen propagates to the nested LazyLists (Crashlytics " +
                "c7c8cccad09f72bd7bb95455226109b8, §6.Q).",
            source.contains(".fillMaxHeight(0.9f)"),
        )
    }

    @Test
    fun pagesScreenChild_isWeighted() {
        assertTrue(
            "CategorySelectionDialog.kt PagesScreen call MUST pass " +
                "modifier = Modifier.weight(1f) — without it the Column measures " +
                "the pager with unbounded height and the nested fillMaxHeight() " +
                "LazyLists crash at measure time (Crashlytics " +
                "c7c8cccad09f72bd7bb95455226109b8, §6.Q).",
            Regex("""PagesScreen\(\s*modifier\s*=\s*Modifier\.weight\(1f\)""").containsMatchIn(source),
        )
    }

    @Test
    fun dialogContentColumn_isNotThePreFixUnboundedShape() {
        assertFalse(
            "CategorySelectionDialog.kt MUST NOT revert to the pre-fix shape " +
                "`Column(modifier = Modifier.clip(AppTheme.shapes.large))` — that " +
                "unbounded Column is the exact Crashlytics " +
                "c7c8cccad09f72bd7bb95455226109b8 crash site.",
            source.contains("Column(modifier = Modifier.clip(AppTheme.shapes.large))"),
        )
    }
}
