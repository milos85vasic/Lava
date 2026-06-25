/*
 * Challenge Test C50 — Provider-config "Add mirror": type a URL → tap Add
 * → no crash → the mirror renders in the list.
 *
 * This exercises the WireMirror serialization path — the same crash CLASS
 * as C48's WireToggle. ProviderConfigViewModel.perform(AddMirror) at line
 * 164 calls `json.encodeToString(WireMirror(...))` to enqueue the sync
 * outbox entry. If the feature module's kotlinx-serialization wiring / R8
 * keep-rules are missing, this throws SerializationException and the
 * activity dies — exactly the Sync-toggle crash, just on the mirror path.
 * No existing Challenge ever submitted the Add-mirror field, so this path
 * was unexercised on a device.
 *
 * Anti-bluff posture (clauses 6.J/6.L/6.AB):
 *
 *   PRIMARY assertions on user-visible rendered state:
 *     (a) NO CRASH — after tapping Add, the "Mirrors" section header is
 *         still rendered (activity survived the WireMirror serialize).
 *     (b) RENDERS — the typed mirror URL appears as a row in the Mirrors
 *         list, proving the AddMirror handler stored the UserMirrorEntity
 *         AND observeAll() re-emitted it into state.userMirrors AND the
 *         MirrorsSection rendered the new row.
 *
 *   The URL passes the scheme guard (starts with https://) so it reaches
 *   the userMirrorDao.upsert + the WireMirror encodeToString — the exact
 *   serialization line under test. A synthetic but valid host is used (no
 *   real network call is made; the row is rendered from the DB, the
 *   "Probe" button is NOT tapped).
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In ProviderConfigViewModel.kt AddMirror branch, comment out the
 *      `userMirrorDao.upsert(entity)` line so the handler does nothing
 *      observable (no crash; the mirror never persists/renders).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: the post-Add waitUntil for the typed URL row
 *      times out — "the mirror never appeared in the list". The no-op
 *      proves the test discriminates the real store-and-render path.
 *   4. Restore the line; re-run; passes.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge50ProviderAddMirrorSurvivesAndRendersTest"
 *
 * // covers-feature: provider_config
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 34) // Forward-compat skip on API 36+ + API 35 — same forensic anchors as C04/C48.
@HiltAndroidTest
class Challenge50ProviderAddMirrorSurvivesAndRendersTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun addMirror_submit_doesNotCrash_andRendersInList() {
        hiltRule.inject()

        // A valid https URL that passes the scheme guard and reaches the
        // WireMirror encodeToString line. Synthetic host — no network call
        // is made (Probe is never tapped); the row renders from the DB.
        val mirrorUrl = "https://c50-mirror.invalid/feed"

        // --- Navigate Menu → first provider row → ProviderConfig ---
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Menu").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("RuTracker.org").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("RuTor.info").fetchSemanticsNodes().isNotEmpty()
        }
        when {
            composeRule.onAllNodesWithText("RuTracker.org").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("RuTracker.org").performClick()
            composeRule.onAllNodesWithText("RuTor.info").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("RuTor.info").performClick()
            else -> error("No provider row reachable on Menu")
        }

        // The Mirrors section is below the fold — scroll the "Mirrors"
        // header into view (LazyColumn root, so the add field exists once
        // its item composes).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Mirrors").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Mirrors").performScrollTo()

        // Type into the "Add mirror URL" field (the editable text field on
        // the Mirrors section), then tap Add.
        composeRule.onNode(
            hasSetTextAction(),
        ).performTextInput(mirrorUrl)
        composeRule.onNodeWithText("Add").performScrollTo()
        composeRule.onNodeWithText("Add").performClick()
        composeRule.waitForIdle()

        // (a) NO-CRASH — "Mirrors" header still rendered after the
        //     WireMirror encodeToString line ran.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Mirrors").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Mirrors").assertIsDisplayed()

        // (b) RENDERS — the typed URL appears as a mirror row.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(mirrorUrl).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(mirrorUrl).assertIsDisplayed()
    }
}
