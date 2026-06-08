/*
 * Challenge Test C5 — View topic detail (DEEP restoration 2026-06-08).
 *
 * The shallow C5 (Phase 2.7, 2026-05-04) only asserted the Search tab was
 * reachable. It was honestly reduced because androidx.navigation:
 * navigation-compose 2.9.0 had a test-teardown lifecycle race when the
 * runner destroyed MainActivity while sitting on a deep route. That race
 * is fixed: navigation-compose was bumped 2.9.0 → 2.9.1 in commit
 * 7e6e7bcb. The DEEP flow is restored here.
 *
 * DEEP FLOW (the real production path a user takes to view a topic):
 *
 *   1. Bypass rule starts the app in the main bottom-tab nav, authorized.
 *   2. Search tab renders (search-history empty/list state).
 *   3. Tap the AppBar SearchButton (content-description "Search") →
 *      SearchResultSideEffect/SearchSideEffect.OpenSearchInput →
 *      SearchInputScreen mounts with the focused TextField.
 *   4. Type a query into the real TextField, submit via the IME search
 *      action (onEnter) → SearchInputAction.SubmitClick →
 *      OpenSearch(filter) → SearchResultScreen mounts.
 *   5. Wait for the real result list to populate. The search crosses the
 *      real network boundary to the active provider. The bypass rule
 *      signals a generic authorized state, so the provider configured at
 *      runtime answers. Each result is a real TopicListItem row.
 *   6. Tap the first result row → SearchResultAction.TopicClick →
 *      SearchResultSideEffect.OpenTopic(id) → TopicScreen mounts.
 *   7. Assert the topic detail rendered: the Torrent action button
 *      (string topic_action_torrent = "Torrent") OR the Magnet action
 *      (topic_action_magnet = "Magnet") is displayed — these are the
 *      user-visible torrent-detail surfaces only the TopicScreen renders.
 *
 * The primary assertion (clause 6.J/§6.AB) is on user-visible TopicScreen
 * state (the Torrent/Magnet action button) reached by traversing the real
 * Search → SearchInput → SearchResult → Topic production chain end to end,
 * with no synthetic shortcut.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In feature/topic/src/main/kotlin/lava/topic/TopicScreen.kt, delete
 *      the `Button(... text = stringResource(R.string.topic_action_torrent)
 *      ...)` and the Magnet `Button` from TorrentAppBar's expandableContent
 *      (the screen still composes — title + comments still render, so this
 *      is a NON-crashing break).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: the waitUntil for "Torrent"/"Magnet" times out and
 *      the final assertion fails with
 *      "Failed to assert that a node matched: (hasText('Torrent') ||
 *      hasText('Magnet'))" — the topic opened but the download/magnet
 *      affordance the user needs is gone.
 *   4. Revert; re-run; test passes.
 *
 * Honest network dependency: step 5 crosses the real network. Like C02/C09/
 * C10, when the active provider/network is unreachable the result list
 * stays empty and the topic-open step cannot proceed; on the gate-host the
 * provider MUST be reachable (real-stack per Seventh Law clause 2). The
 * test fails loudly (timeout) rather than passing on an empty list — there
 * is no silent green.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge05ViewTopicDetailTest"
 *
 * // covers-feature: topic
 */
package lava.app.challenges

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge05ViewTopicDetailTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchAndTapResult_topicDetailRendersTorrentOrMagnetAction() {
        hiltRule.inject()

        // Step 1: main app, Search tab. The SearchButton in the AppBar is
        // the user's entry to the search-input screen (content-desc
        // "Search" from designsystem_action_search).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // Step 2: SearchInputScreen — the focused TextField shows the
        // "Search…" placeholder (designsystem_hint_search). Type + submit
        // via the IME search action wired through onEnter.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…").fetchSemanticsNodes().isNotEmpty()
        }
        // Address the single editable search field by hasSetTextAction()
        // (the "Search…" placeholder disappears once text is entered, so it
        // is NOT a stable handle across type + submit). Type the query then
        // fire the IME Search action (ImeAction.Search → keyboardActions
        // .onSearch → SubmitClick). performTextInput("…\n") would NOT submit:
        // it inserts a newline char, it raises neither KEYCODE_ENTER nor an
        // IME action.
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Step 3: SearchResultScreen — wait for real result rows to load
        // across the real network. Every TopicListItem result row carries a
        // FavoriteButton (content-desc "Favorite"); this is the robust
        // per-row signal that appears ONLY when a result row composes (NOT on
        // the "Nothing found" empty state). An empty-results outcome makes
        // this time out — no silent green.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()
        }

        // Step 4: tap the first result ROW → TopicScreen.
        //
        // IMPORTANT: each TopicListItem is a clickable Surface (onClick =
        // TopicClick) that CONTAINS a separate FavoriteButton IconButton
        // (content-desc "Favorite", also clickable). Clicking the favorite
        // TOGGLES favorite — it does NOT open the topic. So we must click the
        // ROW Surface, not the favorite. The row Surface is a node that
        // (a) hasClickAction() AND (b) has the FavoriteButton as a descendant
        // (hasAnyDescendant(hasContentDescription("Favorite"))). The favorite
        // IconButton itself has the click action but does NOT have a
        // "Favorite" descendant (it IS the favorite), so this matcher selects
        // the row, not the favorite. Tap the first such row.
        composeRule.onAllNodes(
            hasClickAction() and hasAnyDescendant(hasContentDescription("Favorite")),
        ).onFirst().performClick()

        // Step 5: assert the topic detail rendered — the Torrent download
        // action OR the Magnet action is the user-visible surface only the
        // TopicScreen renders (topic_action_torrent / topic_action_magnet).
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Torrent").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Magnet").fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithText("Torrent").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Magnet").fetchSemanticsNodes().isNotEmpty(),
        ) { "TopicScreen must render the Torrent or Magnet download action" }
    }
}
