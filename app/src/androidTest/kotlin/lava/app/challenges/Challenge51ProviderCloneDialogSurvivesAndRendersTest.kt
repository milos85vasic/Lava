/*
 * Challenge Test C51 — Provider-config Clone dialog: open → fill name +
 * URL → tap Clone → no crash → the cloned provider's display name renders.
 *
 * Exercises the Clone interactive surface no existing Challenge drove on a
 * device: tapping "Clone provider…" opens the dialog, and tapping "Clone"
 * runs ProviderConfigViewModel.perform(ConfirmClone) → CloneProviderUseCase
 * → a new ClonedProviderEntity. The clone surfaces back in the provider
 * list, so we navigate back to Menu and assert the new display name
 * renders — a persisted, user-visible outcome.
 *
 * Anti-bluff posture (clauses 6.J/6.L/6.AB):
 *
 *   PRIMARY assertions on user-visible rendered state:
 *     (a) NO CRASH — after tapping Clone, the activity survives (the
 *         provider-config screen / menu is still rendering).
 *     (b) RENDERS — the cloned provider's display name appears in the
 *         provider list on Menu, proving the clone was persisted by the
 *         real CloneProviderUseCase and re-surfaced through the catalog.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In ProviderConfigViewModel.kt ConfirmClone branch, comment out the
 *      `cloneProvider(...)` call so the dialog closes but nothing is
 *      persisted (no crash; the clone never appears).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: the post-clone waitUntil for the clone's display
 *      name on the Menu list times out — "the clone never appeared".
 *   4. Restore the call; re-run; passes.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge51ProviderCloneDialogSurvivesAndRendersTest"
 *
 * // covers-feature: provider_config
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
class Challenge51ProviderCloneDialogSurvivesAndRendersTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cloneDialog_open_fill_confirm_doesNotCrash_andClonedProviderRenders() {
        hiltRule.inject()

        val cloneName = "C51 Clone Mirror"
        val cloneUrl = "https://c51-clone.invalid/"

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

        // --- Open the Clone dialog ("Clone provider…" button) ---
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Clone provider…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Clone provider…").performScrollTo()
        composeRule.onNodeWithText("Clone provider…").performClick()

        // The dialog has rendered when the "Display name" label is present.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Display name").fetchSemanticsNodes().isNotEmpty()
        }

        // Fill the two fields (disambiguated by their label sibling), then
        // tap the dialog's "Clone" confirm button.
        composeRule.onNode(
            hasSetTextAction().and(hasText("Display name")),
        ).performTextInput(cloneName)
        composeRule.onNode(
            hasSetTextAction().and(hasText("Primary URL")),
        ).performTextInput(cloneUrl)
        composeRule.onNodeWithText("Clone").performClick()
        composeRule.waitForIdle()

        // (a) NO-CRASH — the activity survived ConfirmClone. Navigate back
        //     to the Menu provider list (the screen is still alive).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Menu").performClick()

        // (b) RENDERS — the cloned provider's display name now appears in
        //     the provider list, proving CloneProviderUseCase persisted it.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(cloneName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(cloneName).assertIsDisplayed()
    }
}
