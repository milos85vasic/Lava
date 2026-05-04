/*
 * Challenge Test C12 — Project Gutenberg anonymous Continue flow
 * (Phase 4.1b redesign 2026-05-04, SHALLOW).
 *
 * Same shape as C11 (ArchiveOrg). Gutenberg uses gutendex.com as a
 * public unauthenticated JSON API; AuthType.NONE; supportsAnonymous
 * is informational because the AuthType.NONE path always wins.
 *
 * Flow:
 *   1. Onboarding "Select Provider" screen
 *   2. Tap "Project Gutenberg"
 *   3. Tap Continue
 *   4. Main app's Search-history empty state renders
 *
 * Deep-coverage (search gutendex.com for "shakespeare", assert book
 * row) owed pending nav-compose 2.9.0 upgrade.
 *
 * STATUS: requires GutenbergDescriptor.verified=true. Phase 4.1b
 * flips the descriptor in the same commit.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In feature/login/.../ProviderLoginViewModel.kt revert the
 *      AuthType.NONE short-circuit. Same protocol as C11.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: spinner persists; waitUntil for "Search
 *      history" times out.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun pickGutenberg_continue_reachesAuthorizedMainApp() {
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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Continue", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Continue", substring = true, ignoreCase = true)
            .performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
