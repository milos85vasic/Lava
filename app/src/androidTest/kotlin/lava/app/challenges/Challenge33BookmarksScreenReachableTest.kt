/*
 * Challenge Test C33 — Bookmarks screen (feature/bookmarks, package
 * lava.forum.bookmarks) renders its real empty-state content the way a user
 * sees it when they have no forum bookmarks (§6.AE.1 per-feature coverage;
 * §6.AB.3 rendered-state correctness).
 *
 * REWRITE (2026-06-25, W3 UI-coverage-audit): the previous C33 was a
 * §6.AB.3 BLUFF — it only did `Class.forName("lava.forum.bookmarks.BookmarksViewModel")`
 * (classpath presence). A blank/broken BookmarksScreen would have passed it.
 * The classpath assertion is REMOVED. This rewrite drives the EXACT
 * production composable BookmarksScreen renders for BookmarksState.Empty —
 * `emptyItem(titleRes = R.string.forum_screen_bookmarks_empty_title, …)`
 * which expands to `lava.designsystem.component.Empty(…)` (see
 * BookmarksScreen.kt:71-75 + LazyList.kt:98-110) — and asserts on the real
 * user-visible empty-state copy.
 *
 * The Bookmarks list item (`Bookmark`) is private to feature/bookmarks, so
 * the screen's other rendered surface that IS reproducible in isolation is
 * the empty-state. BookmarksState.Empty is the DEFAULT real-user state for a
 * fresh account with no bookmarks — exactly what most users see — so this is
 * the highest-value rendered assertion for this screen. The bookmarks string
 * resources merge into the :app test APK (:app depends on :feature:bookmarks),
 * resolved by name (the R class is module-internal) so the assertion is on
 * the REAL feature copy, not a synthetic string.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   The production Empty composable renders the real Bookmarks empty-state
 *   title ("Bookmarks") AND subtitle ("There will be list of forum
 *   bookmarks") a real user reads when their bookmark list is empty.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In core/designsystem Placeholder.kt, change the subtitle Text to
 *      render the empty string `""` instead of `stringResource(subtitleRes)`
 *      (non-crashing — the title still renders, the layout still composes).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `bookmarksEmptyState_rendersRealCopy` fails on the
 *      subtitle assertion — "could not find any node that satisfies: (Text +
 *      EditableText contains 'There will be list of forum bookmarks')" — the
 *      empty-state subtitle a real user reads is gone.
 *   4. Restore `stringResource(subtitleRes)`; re-run; passes.
 *
 * Honest scope (§6.J / §6.AH): SOURCE-WRITTEN + COMPILE-VERIFIED. EXECUTION
 * against a booted emulator is GATE-HOST-DEFERRED — this macOS host cannot
 * boot the Containers-driven emulator and host-direct is forbidden (§6.AH).
 * This Challenge MUST NOT be recorded as a passing attestation row until it
 * has EXECUTED on a Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: bookmarks
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import lava.designsystem.component.Empty
import lava.designsystem.theme.LavaTheme
import org.junit.Rule
import org.junit.Test

class Challenge33BookmarksScreenReachableTest {

    @get:Rule
    val composeRule = createComposeRule()

    // CHALLENGE: the production empty-state of the Bookmarks screen renders
    // the real feature copy a user reads when they have no bookmarks.
    @Test
    fun bookmarksEmptyState_rendersRealCopy() {
        // Resolve the feature-internal R values by name — the bookmarks
        // resources merge into the :app test APK, but the R class is
        // module-internal so they are looked up by resource name.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageName
        val titleId = ctx.resources.getIdentifier(
            "forum_screen_bookmarks_empty_title",
            "string",
            pkg,
        )
        val subtitleId = ctx.resources.getIdentifier(
            "forum_screen_bookmarks_empty_subtitle",
            "string",
            pkg,
        )
        val illId = ctx.resources.getIdentifier("ill_bookmarks", "drawable", pkg)
        assert(titleId != 0 && subtitleId != 0 && illId != 0) {
            "feature/bookmarks string/drawable resources not found in the app " +
                "test APK — title=$titleId subtitle=$subtitleId ill=$illId"
        }

        composeRule.setContent {
            LavaTheme {
                Empty(
                    titleRes = titleId,
                    subtitleRes = subtitleId,
                    imageRes = illId,
                )
            }
        }

        // The exact copy a real user reads on an empty Bookmarks list. These
        // literals are the values of forum_screen_bookmarks_empty_title /
        // _subtitle in feature/bookmarks/src/main/res/values/strings.xml —
        // if the production resource is renamed/retitled, both the
        // getIdentifier resolution above AND these assertions fail.
        composeRule.onNodeWithText("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithText("There will be list of forum bookmarks").assertIsDisplayed()
    }
}
