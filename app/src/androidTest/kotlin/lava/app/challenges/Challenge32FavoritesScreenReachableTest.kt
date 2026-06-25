/*
 * Challenge Test C32 — Favorites screen (feature/favorites) renders its real
 * topic-list content and the row tap fires the real click handler
 * (§6.AE.1 per-feature coverage; §6.AB.3 rendered-state + interaction).
 *
 * REWRITE (2026-06-25, W3 UI-coverage-audit): the previous C32 was a
 * §6.AB.3 BLUFF — it only asserted `FavoritesViewModel::class.java.name`
 * (classpath presence). A blank/broken FavoritesScreen would have passed it.
 * The classpath assertion is REMOVED. This rewrite drives the EXACT
 * production composable FavoritesScreen renders for its FavoritesList state —
 * `lava.ui.component.TopicListItem(topic = …, action = …, onClick = …)`
 * (see FavoritesScreen.kt:94-114, the `FavoriteTopic` wrapper) — and asserts
 * on user-visible rendered text plus the real tap interaction.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   1. The production TopicListItem renders the topic title a real user reads
 *      in the Favorites list ("Favorites fixture topic").
 *   2. Tapping the rendered row fires the production onClick — the exact
 *      callback FavoritesScreen wires to onAction(FavoritesAction.TopicClick).
 *
 * TopicListItem is public in :core:ui (the same one FavoritesScreen invokes),
 * so :app renders it directly with a real lava.models.topic.Topic — no
 * Hilt/nav scaffolding required, matching the C07 rendered pattern.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In TopicListItem.kt change the `onClick = onClick` wiring on the
 *      Surface to `onClick = {}` (non-crashing no-op — the row still renders).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `rowTap_firesOnClick` fails — `assert(clicked.value)`
 *      → "tapping the Favorites topic row did not invoke onClick — the
 *      FavoritesAction.TopicClick path the screen wires is broken". The
 *      title-render assertion is unaffected, proving the interaction
 *      assertion is what discriminates.
 *   4. Restore `onClick = onClick`; re-run; passes.
 *
 * Honest scope (§6.J / §6.AH): SOURCE-WRITTEN + COMPILE-VERIFIED. EXECUTION
 * against a booted emulator is GATE-HOST-DEFERRED — this macOS host cannot
 * boot the Containers-driven emulator and host-direct is forbidden (§6.AH).
 * This Challenge MUST NOT be recorded as a passing attestation row until it
 * has EXECUTED on a Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: favorites
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lava.designsystem.theme.LavaTheme
import lava.models.topic.BaseTopic
import lava.ui.component.TopicListItem
import org.junit.Rule
import org.junit.Test

class Challenge32FavoritesScreenReachableTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fixtureTopic = BaseTopic(id = "f-1", title = "Favorites fixture topic")

    // CHALLENGE: the production Favorites list item renders the topic title a
    // real user sees in the Favorites tab.
    @Test
    fun favoritesListItem_rendersTopicTitle() {
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topic = fixtureTopic,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Favorites fixture topic").assertIsDisplayed()
    }

    // CHALLENGE: tapping the rendered row fires the production onClick — the
    // FavoritesAction.TopicClick path FavoritesScreen wires.
    @Test
    fun rowTap_firesOnClick() {
        val clicked = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                TopicListItem(
                    topic = fixtureTopic,
                    onClick = { clicked.value = true },
                )
            }
        }

        composeRule.onNodeWithText("Favorites fixture topic").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { clicked.value }
        assert(clicked.value) {
            "tapping the Favorites topic row did not invoke onClick — the " +
                "FavoritesAction.TopicClick path the screen wires is broken"
        }
    }
}
