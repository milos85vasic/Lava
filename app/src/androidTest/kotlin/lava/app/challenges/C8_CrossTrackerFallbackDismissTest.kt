/*
 * SP-3a Phase 5 Challenge Test C8 — Cross-tracker fallback modal dismiss.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.16. Same setup as C7 (-PsimulateRuTrackerUnhealthy=true): all
 * RuTracker mirrors UNHEALTHY → search → modal appears. This test taps
 * "Cancel" instead of "Try RuTor" and asserts:
 *
 *   - The modal closes.
 *   - An explicit failure UI renders (Snackbar with "Search failed" or
 *     equivalent).
 *   - NO RuTor results render — there must be no silent fallback.
 *
 * STATUS: Source-only at SP-3a Phase 5 commit. Operator runs on a real
 * device per Task 5.22.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In feature/search_result/src/main/kotlin/lava/feature/searchresult/
 *      SearchResultViewModel.kt mutate the dismiss handler to silently
 *      retry on RuTor anyway (call sdk.search() with the alt tracker
 *      as if "accept" had been tapped).
 *   2. Re-run on real device with -PsimulateRuTrackerUnhealthy=true.
 *   3. Expected failure: RuTor results DO render after Cancel; the
 *      "Results from RuTor" banner is visible; the assertion that
 *      banner is NOT visible fires.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class C8_CrossTrackerFallbackDismissTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rutrackerAllMirrorsUnhealthy_searchModalDismissed_explicitFailureUiNoSilentFallback() {
        hiltRule.inject()

        // Step 1: search (modal should appear via the unhealthy seed).
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Try RuTor", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 2: tap Cancel.
        composeRule.onNodeWithText("Cancel").performClick()

        // Step 3: assert explicit failure UI (snackbar or equivalent).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search failed", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Search failed", substring = true)
            .assertIsDisplayed()

        // Step 4: assert NO silent fallback — the "Results from RuTor"
        // banner MUST NOT be present (Sixth Law clause 3 negative
        // assertion: absence of a UI element is itself a user-visible
        // signal).
        composeRule.onNodeWithText("Results from RuTor", substring = true)
            .assertIsNotDisplayed()
    }
}
