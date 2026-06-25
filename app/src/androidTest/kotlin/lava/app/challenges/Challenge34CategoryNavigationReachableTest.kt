/*
 * Challenge Test C34 — Category screen (feature/category, package
 * lava.forum.category) renders its real list content and the row tap fires
 * the real click handler (§6.AE.1 per-feature coverage; §6.AB.3
 * rendered-state + interaction).
 *
 * REWRITE (2026-06-25, W3 UI-coverage-audit): the previous C34 was a
 * §6.AB.3 BLUFF — it only built `::openCategory` and asserted
 * `ref.toString().isNotEmpty()` (a function reference is never empty, so
 * the test could not fail on any real defect). The reference assertion is
 * REMOVED. This rewrite drives the EXACT production composable
 * CategoryScreen renders for a `CategoryItem.Topic` row —
 * `lava.ui.component.TopicListItem(topicModel = …, showCategory = false,
 * onClick = …, onFavoriteClick = …)` (see CategoryScreen.kt:160-170) — and
 * asserts on user-visible rendered text plus the real tap interaction.
 *
 * The Category folder-row composable (`Category`) is private to
 * feature/category; the topic row (TopicListItem) is the public production
 * surface a Category page renders for torrent/topic results, so :app renders
 * it directly with a real lava.models.topic.TopicModel.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   1. The production TopicListItem renders the topic title a real user reads
 *      inside a Category page ("Category fixture topic").
 *   2. Tapping the rendered row fires the production onClick — the exact
 *      callback CategoryScreen wires to onAction(CategoryAction.TopicClick).
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In TopicListItem.kt change the `onClick = onClick` wiring on the
 *      Surface to `onClick = {}` (non-crashing no-op — the row still renders).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `categoryTopicRowTap_firesOnClick` fails —
 *      `assert(clicked.value)` → "tapping the Category topic row did not
 *      invoke onClick — the CategoryAction.TopicClick path the screen wires
 *      is broken". The title-render assertion is unaffected, proving the
 *      interaction assertion is what discriminates.
 *   4. Restore `onClick = onClick`; re-run; passes.
 *
 * Honest scope (§6.J / §6.AH): SOURCE-WRITTEN + COMPILE-VERIFIED. EXECUTION
 * against a booted emulator is GATE-HOST-DEFERRED — this macOS host cannot
 * boot the Containers-driven emulator and host-direct is forbidden (§6.AH).
 * This Challenge MUST NOT be recorded as a passing attestation row until it
 * has EXECUTED on a Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: category
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lava.designsystem.theme.LavaTheme
import lava.models.topic.BaseTopic
import lava.models.topic.TopicModel
import lava.ui.component.TopicListItem
import org.junit.Rule
import org.junit.Test

class Challenge34CategoryNavigationReachableTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fixture = TopicModel(
        topic = BaseTopic(id = "c-1", title = "Category fixture topic"),
    )

    // CHALLENGE: the production Category topic row renders the topic title a
    // real user sees inside a forum Category page.
    @Test
    fun categoryTopicRow_rendersTopicTitle() {
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topicModel = fixture,
                    showCategory = false,
                    onClick = {},
                    onFavoriteClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Category fixture topic").assertIsDisplayed()
    }

    // CHALLENGE: tapping the rendered row fires the production onClick — the
    // CategoryAction.TopicClick path CategoryScreen wires.
    @Test
    fun categoryTopicRowTap_firesOnClick() {
        val clicked = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topicModel = fixture,
                    showCategory = false,
                    onClick = { clicked.value = true },
                    onFavoriteClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Category fixture topic").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { clicked.value }
        assert(clicked.value) {
            "tapping the Category topic row did not invoke onClick — the " +
                "CategoryAction.TopicClick path the screen wires is broken"
        }
    }
}
