/*
 * Challenge Test C4 — Tap a Menu provider row → land on Provider Config.
 *
 * SP-4 Phase C (2026-05-13) rewrite. The pre-Phase-C C4 asserted that
 * the Menu's "Trackers" entry was reachable; Phase C deletes that entry
 * because the per-provider config screen reachable by tapping a provider
 * row absorbs the same capability (and adds credentials assignment, sync
 * toggle, mirrors, anonymous mode, clone). The rewritten C4 asserts the
 * new path: Menu → tap first provider row → ProviderConfig renders.
 *
 * Anti-bluff posture (clauses 6.J/6.L):
 *
 *   The assertion is on user-visible state — after the tap, a section
 *   label only the ProviderConfig screen renders ("Sync this provider")
 *   must be present in the semantic tree. A successful tap is necessary
 *   but not sufficient — the Compose tree must actually render the new
 *   screen's content. A deliberate-mutation rehearsal in MenuViewModel
 *   (remove the `postSideEffect(MenuSideEffect.OpenProviderConfig(...))`
 *   line) makes the tap a no-op, the provider-row stays on Menu, and
 *   the waitUntil for "Sync this provider" times out with
 *   ComposeTimeoutException — clear failure signal.
 *
 *   This is honest non-shallow coverage: the test traverses real Menu
 *   → real MenuViewModel → real MenuSideEffect → real navigation →
 *   real ProviderConfigScreen, with no synthetic shortcuts.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In feature/menu/src/main/kotlin/lava/menu/MenuViewModel.kt,
 *      comment out the body of `onOpenProviderConfig(providerId)` so
 *      the function emits nothing.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: waitUntil for "Sync this provider" times out
 *      with ComposeTimeoutException — the tap routes nowhere, the
 *      ProviderConfig screen never composes.
 *   4. Revert; re-run; test passes.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge04ProviderRowOpensConfigTest"
 *
 * The shallow gap C04 carried (nav-compose 2.9.0 lifecycle race when
 * sitting on a deep route) is no longer relevant: ProviderConfig is
 * reachable via the same `addProviderConfig` registration in the
 * top-level navigation graph, and the tests above C04 (C00, C01) prove
 * the activity destroys cleanly from the Menu surface.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge04ProviderRowOpensConfigTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun menuProviderRow_opensProviderConfigScreen() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Menu").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("RuTracker.org").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("RuTor.info").fetchSemanticsNodes().isNotEmpty()
        }

        val rutrackerNodes = composeRule.onAllNodesWithText("RuTracker.org").fetchSemanticsNodes()
        val rutorNodes = composeRule.onAllNodesWithText("RuTor.info").fetchSemanticsNodes()
        when {
            rutrackerNodes.isNotEmpty() -> composeRule.onNodeWithText("RuTracker.org").performClick()
            rutorNodes.isNotEmpty() -> composeRule.onNodeWithText("RuTor.info").performClick()
            else -> error("No provider row reachable on Menu")
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Sync this provider").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Sync this provider").assertIsDisplayed()
    }
}
