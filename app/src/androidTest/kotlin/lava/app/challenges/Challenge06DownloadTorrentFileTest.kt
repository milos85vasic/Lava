/*
 * Challenge Test C6 — Download .torrent (DEEP restoration 2026-06-08).
 *
 * The shallow C6 (Phase 2.8, 2026-05-04) only asserted the Forum tab was
 * reachable, reduced because of the navigation-compose 2.9.0 teardown race
 * (fixed: bumped 2.9.0 → 2.9.1 in commit 7e6e7bcb). The DEEP flow is
 * restored here.
 *
 * NOTE ON PRODUCTION SHAPE (anti-bluff honesty, §6.J): the pre-shallow C6
 * (commit f21b4d94) asserted a bencoded .torrent file was written to
 * `filesDir/downloads` and parsed `6:pieces`. That is NOT the current
 * production flow. Today the user taps the "Torrent" action in TopicScreen's
 * TorrentAppBar; TopicAction.TorrentFileClick fires; TopicViewModel posts
 * TopicSideEffect.ShowDownloadProgress; TopicScreen shows a DownloadDialog
 * whose terminal state renders "Download completed"
 * (topic_file_download_completed) with an "Open file" action. The
 * user-visible outcome is the DownloadDialog, so THAT is what this Challenge
 * asserts — the real surface the user sees, not a synthetic file probe.
 *
 * DEEP FLOW:
 *   1. Bypass rule → main app, authorized.
 *   2. Search tab → SearchButton → SearchInputScreen → type + IME submit.
 *   3. SearchResultScreen → wait for real result rows → tap first row.
 *   4. TopicScreen renders → tap the "Torrent" action button.
 *   5. The DownloadDialog appears: "Download in progress" then "Download
 *      completed" (topic_file_download_in_progress / _completed). Assert the
 *      download dialog rendered — the user-visible download outcome.
 *
 * The primary assertion (clause 6.J/§6.AB) is on user-visible state (the
 * DownloadDialog title) reached by traversing the real Search → SearchInput
 * → SearchResult → Topic → Download production chain end to end.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In feature/topic/src/main/kotlin/lava/topic/TopicViewModel.kt, in the
 *      TorrentFileClick handler, drop the
 *      `postSideEffect(TopicSideEffect.ShowDownloadProgress)` line (the
 *      screen still composes; tapping Torrent simply does nothing — a
 *      NON-crashing break, exactly the C37 dead-ended-side-effect class).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: no DownloadDialog composes; the waitUntil for
 *      "Download completed"/"Download in progress" times out and the
 *      assertion fails with "Failed to assert that a node matched:
 *      (hasText('Download completed') || hasText('Download in progress'))".
 *   4. Revert; re-run; test passes.
 *
 * Honest network dependency: same as C05 — step 3 crosses the real network.
 * The test fails loudly (timeout) on an empty result list; no silent green.
 * Some topics expose only a magnet (no .torrent); if the first result has no
 * Torrent action the test taps Magnet instead and asserts the MagnetDialog
 * link is shown — both are real download affordances.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge06DownloadTorrentFileTest"
 *
 * // covers-feature: topic
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
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
class Challenge06DownloadTorrentFileTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchTapResult_tapTorrent_downloadDialogRenders() {
        hiltRule.inject()

        // Step 1: main app → tap the AppBar Search action.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // Step 2: SearchInputScreen — type the query into the single editable
        // field (addressed by hasSetTextAction since the "Search…" placeholder
        // disappears once text is entered) then fire the IME Search action.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Step 3: SearchResultScreen — wait for a real result ROW. Every
        // TopicListItem row carries a FavoriteButton (content-desc "Favorite")
        // — the robust per-row signal that only renders when a row composes
        // (NOT on the "Nothing found" empty state). Then tap the ROW Surface
        // (hasClickAction + has a "Favorite" descendant), NOT the favorite
        // icon itself — see C05 for the rationale on this matcher.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(
            hasClickAction() and hasAnyDescendant(hasContentDescription("Favorite")),
        ).onFirst().performClick()

        // Step 4: TopicScreen — wait for the Torrent OR Magnet action.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Torrent").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Magnet").fetchSemanticsNodes().isNotEmpty()
        }

        val hasTorrent = composeRule.onAllNodesWithText("Torrent").fetchSemanticsNodes().isNotEmpty()
        if (hasTorrent) {
            // Step 5a: tap Torrent → DownloadDialog. Note: on devices with
            // runtime WRITE_EXTERNAL_STORAGE not granted the tap shows the
            // permission rationale dialog first; the gate-host AVD grants the
            // permission via the test runner so the download proceeds.
            composeRule.onNodeWithText("Torrent").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithText("Download completed").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText("Download in progress").fetchSemanticsNodes().isNotEmpty()
            }
            require(
                composeRule.onAllNodesWithText("Download completed").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText("Download in progress").fetchSemanticsNodes().isNotEmpty(),
            ) { "Tapping Torrent must surface the DownloadDialog (in-progress or completed)" }
        } else {
            // Step 5b: torrent-less topic — tap Magnet → MagnetDialog shows
            // the magnet link with Share/Open actions (designsystem_action_open).
            composeRule.onNodeWithText("Magnet").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty()
            }
            require(
                composeRule.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty(),
            ) { "Tapping Magnet must surface the MagnetDialog with an Open action" }
        }
    }
}
