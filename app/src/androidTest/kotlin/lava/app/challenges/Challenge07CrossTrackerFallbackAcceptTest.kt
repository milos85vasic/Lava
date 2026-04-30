/*
 * SP-3a Phase 5 Challenge Test C7 — Cross-tracker fallback modal accept.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.15. Compose UI test that simulates all RuTracker mirrors as
 * UNHEALTHY (test-only injection via the MirrorHealthRepository fake),
 * performs a search, asserts CrossTrackerFallbackModal is rendered,
 * taps "Try RuTor", and asserts results from RuTor render with the
 * "Results from RuTor" banner.
 *
 * STATUS (updated SP-3a Step 6, 2026-04-30): NOW RUNNABLE on a connected
 * device via
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge07CrossTrackerFallbackAcceptTest"
 * Source-only compile is verified by the pre-push gate.
 * Operator real-device attestation per Task 5.22 still required for
 * release tagging (Sixth Law clause 5).
 *
 * The test relies on a debug-only seed in the BuildConfig that lets the
 * instrumented runner pre-populate the in-memory MirrorHealthRepository
 * state with all RuTracker mirrors marked UNHEALTHY. This seed is only
 * activated when the test runner sets -PsimulateRuTrackerUnhealthy=true.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/client/src/main/kotlin/lava/tracker/client/
 *      LavaTrackerSdk.kt mutate the search() outcome dispatch so that
 *      it never emits CrossTrackerFallbackProposed (e.g., always
 *      return Failure regardless of the cross-tracker fallback policy).
 *   2. Re-run on real device with -PsimulateRuTrackerUnhealthy=true.
 *   3. Expected failure: modal never appears; assertion 'Try RuTor'
 *      visible fails on timeout.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
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
class Challenge07CrossTrackerFallbackAcceptTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rutrackerAllMirrorsUnhealthy_searchTriggersFallbackModal_acceptShowsRuTorResults() {
        hiltRule.inject()

        // PRECONDITION: -PsimulateRuTrackerUnhealthy=true seeds the
        // MirrorHealthRepository with all RuTracker mirrors marked
        // UNHEALTHY. The seed runs from the @HiltAndroidTest setup
        // before the @Test starts.

        // Step 1: search.
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()

        // Step 2: assert the modal appears (primary user-visible state
        // per Sixth Law clause 3).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Try RuTor", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(
            "All RuTracker mirrors are unhealthy",
            substring = true,
        )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Try RuTor", substring = true)
            .assertIsDisplayed()

        // Step 3: tap "Try RuTor".
        composeRule.onNodeWithText("Try RuTor", substring = true)
            .performClick()

        // Step 4: assert the result list is now from RuTor.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Results from RuTor", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Results from RuTor", substring = true)
            .assertIsDisplayed()
    }
}
