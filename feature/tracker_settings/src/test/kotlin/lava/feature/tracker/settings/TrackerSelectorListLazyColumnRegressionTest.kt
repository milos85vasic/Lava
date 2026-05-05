package lava.feature.tracker.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validation test for §6.O — Crashlytics-Resolved Issue Coverage Mandate.
 *
 * Forensic anchor: 2026-05-05 operator report — "Opening Trackers from
 * Settings crashes the app." Root cause: `TrackerSelectorList`
 * Composable used `LazyColumn` while nested inside `TrackerSettingsScreen`'s
 * `Column(verticalScroll(rememberScrollState()))`. Compose throws
 * `IllegalStateException: Vertically scrollable component was measured
 * with an infinite maximum height constraint` because the parent's
 * `verticalScroll` and the child's `LazyColumn` both want unbounded
 * vertical space.
 *
 * Closure log: `.lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md`.
 *
 * Fix: replaced `LazyColumn` with a plain `Column` (the tracker list is
 * bounded — typically ≤ 6 entries — so virtualization is unnecessary).
 *
 * What this test asserts: a structural invariant on the source file.
 * Specifically:
 *   1. `TrackerSelectorList.kt` MUST NOT import `LazyColumn` or `LazyRow`.
 *   2. `TrackerSelectorList.kt` MUST NOT use the symbol `LazyColumn(`
 *      (excluding the doc-comment that references the removed pattern).
 *
 * This is a deliberately structural test rather than a behavioral one:
 * the behavioral test (the Compose UI Challenge Test C14) lives at
 * `app/src/androidTest/kotlin/lava/app/challenges/Challenge14TrackerSettingsOpenTest.kt`
 * and is the load-bearing acceptance gate per §6.J/§6.L. This file is
 * the regression-immunity guard that runs in the §6.J cheap pre-push
 * gate (`scripts/ci.sh --changed-only`) BEFORE the matrix run.
 *
 * Falsifiability rehearsal:
 *   1. Re-introduce `import androidx.compose.foundation.lazy.LazyColumn`
 *      and `LazyColumn(...) { ... }` in `TrackerSelectorList.kt`.
 *   2. Run `./gradlew :feature:tracker_settings:test --tests
 *      'lava.feature.tracker.settings.TrackerSelectorListLazyColumnRegressionTest'`.
 *   3. Both assertions fail with messages naming the regressed pattern.
 *   4. Revert; tests pass.
 */
class TrackerSelectorListLazyColumnRegressionTest {

    private val source: String by lazy {
        // Locate the file relative to the project root via the cwd Gradle
        // sets when running :feature:tracker_settings:test.
        val candidates = listOf(
            "src/main/kotlin/lava/feature/tracker/settings/components/TrackerSelectorList.kt",
            "feature/tracker_settings/src/main/kotlin/lava/feature/tracker/settings/components/TrackerSelectorList.kt",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("TrackerSelectorList.kt not found via any candidate path: $candidates")
        file.readText()
    }

    @Test
    fun `TrackerSelectorList does not import LazyColumn`() {
        val imports = source.lineSequence()
            .filter { it.startsWith("import ") }
            .toList()
        // Lines that "look like" a LazyColumn import — strict prefix match.
        val lazyImports = imports.filter {
            it.contains("androidx.compose.foundation.lazy.LazyColumn") ||
                it.contains("androidx.compose.foundation.lazy.LazyRow") ||
                it.contains("androidx.compose.foundation.lazy.items")
        }
        assertTrue(
            "TrackerSelectorList.kt MUST NOT import LazyColumn / LazyRow / lazy.items. " +
                "The Compose 'Vertically scrollable component was measured with an infinite " +
                "maximum height constraint' crash recurs whenever LazyColumn is nested inside " +
                "the parent screen's Column(verticalScroll(...)). See closure log at " +
                ".lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md. " +
                "Found regression imports: $lazyImports",
            lazyImports.isEmpty(),
        )
    }

    @Test
    fun `TrackerSelectorList body does not invoke LazyColumn`() {
        // Filter to non-comment lines so the doc-comment that references
        // the removed pattern doesn't false-positive.
        val nonCommentBody = source.lineSequence()
            .filterIndexed { _, line ->
                val trimmed = line.trim()
                !trimmed.startsWith("*") &&
                    !trimmed.startsWith("//") &&
                    !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("/**")
            }
            .joinToString("\n")
        val hasLazyColumn = nonCommentBody.contains("LazyColumn(")
        val hasLazyRow = nonCommentBody.contains("LazyRow(")
        assertFalse(
            "TrackerSelectorList.kt MUST NOT call LazyColumn(...) or LazyRow(...) in real " +
                "code. The 2026-05-05 Crashlytics fix replaced LazyColumn with a plain Column " +
                "because the parent screen uses verticalScroll, and nested-scroll containers " +
                "cause Compose to throw IllegalStateException at measure time. " +
                "See .lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md.",
            hasLazyColumn || hasLazyRow,
        )
    }
}
