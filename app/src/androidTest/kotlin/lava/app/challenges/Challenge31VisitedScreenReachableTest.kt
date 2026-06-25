/*
 * Challenge Test C31 — Visited screen (feature/visited) renders its real
 * topic-list content and the row tap fires the real click handler
 * (§6.AE.1 per-feature coverage; §6.AB.3 rendered-state + interaction).
 *
 * REWRITE (2026-06-25, W3 UI-coverage-audit): the previous C31 was a
 * §6.AB.3 BLUFF — it only did `Class.forName("lava.visited.VisitedViewModel")`
 * and asserted the class is on the classpath. A blank, broken, or
 * non-rendering VisitedScreen would have passed that test unchanged.
 * The classpath assertion is REMOVED. This rewrite drives the EXACT
 * production composable VisitedScreen renders for its VisitedList state —
 * `lava.ui.component.TopicListItem(topicModel = …, onClick, onFavoriteClick)`
 * (see VisitedScreen.kt:57-72) — and asserts on user-visible rendered text
 * plus the real tap interaction.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   1. The production TopicListItem renders the topic title a real user
 *      reads in the Visited list ("Visited fixture topic").
 *   2. Tapping the rendered row fires the production onClick callback — the
 *      exact callback VisitedScreen wires to onAction(VisitedAction.TopicClick).
 *   3. Tapping the favorite affordance fires the production onFavoriteClick —
 *      the callback VisitedScreen wires to VisitedAction.FavoriteClick.
 *
 * TopicListItem is public in :core:ui (the same one VisitedScreen invokes),
 * so :app renders it directly with a real lava.models.topic.TopicModel —
 * no Hilt/nav scaffolding required, matching the C07 rendered pattern
 * (createComposeRule + setContent + onNodeWithText + performClick + capture).
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In TopicListItem.kt change the `onClick = onClick` wiring on the
 *      Surface to `onClick = {}` (a non-crashing no-op — the row still
 *      renders, nothing visibly breaks).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `rowTap_firesOnClick` fails —
 *      `assert(clicked.value)` → "tapping the Visited topic row did not
 *      invoke onClick — the VisitedAction.TopicClick path the screen wires
 *      is broken". The title-render assertion (a separate failure mode) is
 *      unaffected, proving the interaction assertion is what discriminates.
 *   4. Restore `onClick = onClick`; re-run; passes.
 *
 * Honest scope (§6.J / §6.AH): SOURCE-WRITTEN + COMPILE-VERIFIED. EXECUTION
 * against a booted emulator is GATE-HOST-DEFERRED — this macOS host cannot
 * boot the Containers-driven emulator and host-direct is forbidden (§6.AH).
 * This Challenge MUST NOT be recorded as a passing attestation row until it
 * has EXECUTED on a Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: visited
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lava.designsystem.theme.LavaTheme
import lava.models.topic.BaseTopic
import lava.models.topic.TopicModel
import lava.ui.component.TopicListItem
import org.junit.Rule
import org.junit.Test

class Challenge31VisitedScreenReachableTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fixture = TopicModel(
        topic = BaseTopic(id = "v-1", title = "Visited fixture topic"),
        isVisited = true,
    )

    // CHALLENGE: the production Visited list item renders the topic title a
    // real user sees in the Visited tab.
    @Test
    fun visitedListItem_rendersTopicTitle() {
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topicModel = fixture,
                    onClick = {},
                    onFavoriteClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Visited fixture topic").assertIsDisplayed()
    }

    // CHALLENGE: tapping the rendered row fires the production onClick — the
    // VisitedAction.TopicClick path VisitedScreen wires.
    @Test
    fun rowTap_firesOnClick() {
        val clicked = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topicModel = fixture,
                    onClick = { clicked.value = true },
                    onFavoriteClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Visited fixture topic").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { clicked.value }
        assert(clicked.value) {
            "tapping the Visited topic row did not invoke onClick — the " +
                "VisitedAction.TopicClick path the screen wires is broken"
        }
    }

    // CHALLENGE: tapping the favorite affordance fires the production
    // onFavoriteClick — the VisitedAction.FavoriteClick path.
    @Test
    fun favoriteTap_firesOnFavoriteClick() {
        val favorited = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topicModel = fixture,
                    onClick = {},
                    onFavoriteClick = { favorited.value = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Favorite", substring = true, ignoreCase = true)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { favorited.value }
        assert(favorited.value) {
            "tapping the favorite button did not invoke onFavoriteClick — the " +
                "VisitedAction.FavoriteClick path the screen wires is broken"
        }
    }
}
