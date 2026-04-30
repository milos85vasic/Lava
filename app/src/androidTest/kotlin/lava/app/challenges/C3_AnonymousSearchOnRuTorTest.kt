/*
 * SP-3a Phase 5 Challenge Test C3 — Anonymous search on RuTor.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.11. Compose UI test that switches the active tracker to RuTor
 * and performs an anonymous search (no login required, decision 7b-ii).
 * Asserts at least one result row renders with parseable size + seeders.
 *
 * STATUS: Source-only at SP-3a Phase 5 commit. Operator runs on a real
 * device per Task 5.22.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/rutor/src/main/kotlin/lava/tracker/rutor/
 *      RuTorDescriptor.kt remove TrackerCapability.SEARCH from the
 *      capabilities set.
 *   2. Re-run on real device.
 *   3. Expected failure: RuTor.getFeature<Searchable>() returns null;
 *      the search UI surfaces "Search not supported on this tracker";
 *      the result-row assertion fails.
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
class C3_AnonymousSearchOnRuTorTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchToRuTor_anonymousSearchUbuntu_resultRowVisibleWithSizeAndSeeders() {
        hiltRule.inject()

        // Step 1: switch active tracker to RuTor.
        composeRule.onNodeWithText("Menu").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Trackers").performClick()
        composeRule.onNodeWithText("RuTor").performClick()
        composeRule.onNodeWithText("RuTor (active)").assertIsDisplayed()

        // Step 2: navigate back to Search and search for "ubuntu".
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()

        // Step 3: wait for result list (anonymous, no login round-trip).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("seeders", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 4: assert primary user-visible state (Sixth Law clause 3).
        composeRule.onNodeWithText("seeders", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("MB", substring = true)
            .assertIsDisplayed()
    }
}
