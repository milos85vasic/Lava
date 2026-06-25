/*
 * Challenge Test C49 — Provider-config "Anonymous" toggle:
 * click → no crash → checked-state flips → state persists.
 *
 * Sibling of C48. The Anonymous Switch travels through a DIFFERENT
 * persist path than Sync — ToggleAnonymous routes to
 * `ProviderConfigRepository.setUseAnonymous(...)` (provider_configs DB
 * column), NOT through the json.encodeToString(WireToggle) outbox path.
 * It is in the same crash CLASS family, though: any handler that the
 * existing Challenges never performClick()'d is an untested surface.
 * C49 closes the Anonymous-toggle hole the same way C48 closes Sync.
 *
 * Determinism: the AnonymousSection only renders when
 * `descriptor.supportsAnonymous == true`. RuTorDescriptor sets
 * supportsAnonymous = true, so the test targets the RuTor.info row
 * specifically — the Anonymous Switch is GUARANTEED present. (If a
 * future refactor flips RuTor's flag the test fails loudly at the
 * waitUntil rather than green-on-skip — that is intentional per §6.J.)
 *
 * Anti-bluff posture (clauses 6.J/6.L/6.AB):
 *
 *   PRIMARY assertions on user-visible rendered state:
 *     (a) NO CRASH — "Anonymous" Body still in the tree after the click.
 *     (b) STATE FLIP — the Anonymous Switch's checked-state flips in the
 *         rendered UI, proving ToggleAnonymous persisted to
 *         provider_configs.use_anonymous AND observeAll() re-bound it
 *         into state.anonymous.
 *     (c) PERSISTS — second read after settle shows the new state holds.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In ProviderConfigViewModel.kt ToggleAnonymous branch, comment out
 *      the `providerConfigRepository.setUseAnonymous(providerId, !current)`
 *      line so the handler is a no-op (no crash; nothing persists).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: the post-click assertIsOn()/assertIsOff() on the
 *      Anonymous Switch fails — the rendered toggle never flipped.
 *   4. Restore the line; re-run; passes.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge49ProviderAnonymousToggleSurvivesAndPersistsTest"
 *
 * // covers-feature: provider_config
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 34) // Forward-compat skip on API 36+ + API 35 — same forensic anchors as C04/C48.
@HiltAndroidTest
class Challenge49ProviderAnonymousToggleSurvivesAndPersistsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anonymousToggle_click_doesNotCrash_flipsCheckedState_andPersists() {
        hiltRule.inject()

        // --- Navigate Menu → RuTor.info (supportsAnonymous=true) → ProviderConfig ---
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Menu").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("RuTor.info").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("RuTor.info").performClick()

        // The Anonymous section is GUARANTEED present for RuTor.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Anonymous").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Anonymous").performScrollTo()

        // The Anonymous Switch is the toggleable node that is a sibling of
        // the "Anonymous" label (disambiguates from the Sync Switch above).
        val anonSwitch = {
            composeRule.onNode(isToggleable().and(hasAnySibling(hasText("Anonymous"))))
        }
        val wasOn = runCatching { anonSwitch().assertIsOn() }.isSuccess

        // --- THE CLICK on the previously-untested Anonymous handler ---
        anonSwitch().performClick()
        composeRule.waitForIdle()

        // (a) NO-CRASH — "Anonymous" Body still rendered.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Anonymous").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Anonymous").assertIsDisplayed()

        // (b) STATE FLIP — wait for observeAll() to re-bind the persisted value.
        if (wasOn) {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { anonSwitch().assertIsOff() }.isSuccess
            }
            anonSwitch().assertIsOff()
        } else {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { anonSwitch().assertIsOn() }.isSuccess
            }
            anonSwitch().assertIsOn()
        }

        // (c) PERSISTS — second read after settle.
        composeRule.waitForIdle()
        if (wasOn) anonSwitch().assertIsOff() else anonSwitch().assertIsOn()
    }
}
