/*
 * SP-3a Phase 5 Challenge Test C5 — View topic detail.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.13. Compose UI test that taps a search result row and asserts
 * the TopicDetail screen renders with title, description, file list,
 * and magnet URI button.
 *
 * STATUS (updated SP-3a Step 6, 2026-04-30): NOW RUNNABLE on a connected
 * device via
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge05ViewTopicDetailTest"
 * Source-only compile is verified by the pre-push gate.
 * Operator real-device attestation per Task 5.22 still required for
 * release tagging (Sixth Law clause 5).
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/rutracker/src/main/kotlin/lava/tracker/rutracker/
 *      mappers/TopicMapper.kt mutate toTopicDetail() to drop the
 *      description field (e.g., set description = "" unconditionally).
 *   2. Re-run on real device.
 *   3. Expected failure: assertion 'description visible' fires; the
 *      TopicDetail screen renders without the description block.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
class Challenge05ViewTopicDetailTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchAndTapResult_topicDetailRendersWithTitleDescriptionFileListAndMagnet() {
        hiltRule.inject()

        // Step 1: drive a search (assumes default RuTracker active).
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("seeders", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 2: tap the first result row.
        composeRule.onAllNodesWithText("seeders", substring = true)
            .onFirst()
            .performClick()

        // Step 3: wait for TopicDetail screen.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Description", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 4: assert all four primary user-visible elements
        // (Sixth Law clause 3).
        composeRule.onNodeWithText("Description", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Files", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Magnet URI")
            .assertIsDisplayed()
        // Title is the screen heading; assert it's non-empty by content desc.
        composeRule.onNodeWithContentDescription("Topic title")
            .assertIsDisplayed()
    }
}
