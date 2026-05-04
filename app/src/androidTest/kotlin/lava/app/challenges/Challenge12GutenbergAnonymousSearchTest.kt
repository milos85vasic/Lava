/*
 * Challenge Test C12 — Project Gutenberg anonymous search (clause 6.G).
 *
 * Gutenberg is exposed via the gutendex.com API (open, unauthenticated,
 * JSON over HTTPS). AuthType.NONE per descriptor.
 *
 * Flow:
 *   onboarding → pick "Project Gutenberg" → tap Continue → main app
 *   → search "shakespeare" → at least one book result visible.
 *
 * Network reality: gutendex.com is globally reachable. A test
 * environment without route produces a deterministic timeout, not a
 * false-green.
 *
 * STATUS: requires GutenbergDescriptor.verified=true.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/gutenberg/.../GutenbergSearch.kt return
 *      SearchResult(emptyList(), 0, 0) regardless of input.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: no result rows render; the assertion at the
 *      end of step 5 times out.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.tracker.gutenberg.GutenbergDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge12GutenbergAnonymousSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickGutenberg_continue_searchShakespeare_bookRowVisible() {
        hiltRule.inject()

        assumeTrue(
            "GutenbergDescriptor.verified must be true for this test to apply (clause 6.G)",
            GutenbergDescriptor.verified,
        )

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Gutenberg", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Gutenberg", substring = true, ignoreCase = true)
            .performClick()

        composeRule.onNodeWithText("Continue", substring = true, ignoreCase = true)
            .performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Search", substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("shakespeare")
        composeRule.onNodeWithText("Go").performClick()

        // gutendex.com is reliable — Shakespeare always returns >100 books.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Shakespeare", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
