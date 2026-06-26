/*
 * Challenge Test C48 — Provider-config "Sync this provider" toggle:
 * click → no crash → checked-state flips → state persists across recompose.
 *
 * THE CRASH THIS CATCHES (the load-bearing reason this file exists):
 *
 *   On the release build, tapping the "Sync this provider" Switch
 *   crashed the app. Root cause: ProviderConfigViewModel.perform(
 *   ToggleSync) line 92 calls
 *     `json.encodeToString(WireToggle(providerId, next))`
 *   The private `WireToggle` data class is @Serializable, but under R8
 *   the kotlinx-serialization plugin / keep-rules were not applied to
 *   the feature module, so `encodeToString` threw
 *   kotlinx.serialization.SerializationException ("Serializer for class
 *   'WireToggle' is not found") at runtime — the activity process died.
 *
 *   The prior coverage (Challenge04) ONLY opens the provider-config
 *   screen and asserts "Sync this provider" is DISPLAYED. No existing
 *   Challenge ever performClick()'d the Switch, so the toggle →
 *   serialize → persist → re-render path was never exercised on a
 *   device. C48 is that missing end-to-end click.
 *
 * Anti-bluff posture (clauses 6.J/6.L/6.AB):
 *
 *   PRIMARY assertions are on user-visible rendered state:
 *     (a) NO CRASH — after the click the activity is still alive and the
 *         "Sync this provider" Body is still in the semantic tree. A
 *         SerializationException would tear the process down and the
 *         re-query would raise "no node" / the rule's activity would be
 *         destroyed. This is the load-bearing assertion vs the crash.
 *     (b) STATE FLIP — the Switch's checked-state flips (off→on or
 *         on→off) in the rendered UI after the click, proving the
 *         ToggleSync handler ran AND the persisted value round-tripped
 *         through observeAll() back into `state.syncEnabled`.
 *     (c) PERSISTS — re-reading the toggle node a second time (after the
 *         observe-flow has settled) shows the NEW state, not a snap-back
 *         to the default.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In feature/provider_config/.../ProviderConfigViewModel.kt make the
 *      ToggleSync branch a no-op — comment out the
 *      `toggleDao.upsert(...)` + `outbox.enqueue(...)` lines so the
 *      handler does nothing (the app does NOT crash; the Switch simply
 *      never changes its persisted/observed state).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: the post-click `assertIsOn()` (or assertIsOff)
 *      fails — "failed: assertIsOn ... [Toggleable = Off]" — because the
 *      rendered Switch never flipped. The no-op proves the test
 *      discriminates the real persist-and-rerender path, not just "no
 *      crash".
 *   4. Restore the handler; re-run; passes.
 *
 *   A SECOND, crash-class rehearsal (the production bug itself): leave
 *   the SerializationException in place (remove the
 *   `lava.kotlin.serialization` plugin / R8 keep-rule) → the click
 *   crashes the activity → assertion (a) fails because the
 *   "Sync this provider" node is gone after the activity dies.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge48ProviderSyncToggleSurvivesAndPersistsTest"
 *
 * // covers-feature: provider_config
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.LenientTeardownRule
import lava.app.OnboardingBypassRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 34) // Forward-compat skip on API 36+ (Compose AndroidPrefetchScheduler-needs-Looper) AND API 35 (nav-compose 2.9.0 NavBackStackEntry lifecycle race at runner tear-down). Same forensic anchors as C04: .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json + 2026-05-17-c04-nav-compose-lifecycle-race.json.
@HiltAndroidTest
class Challenge48ProviderSyncToggleSurvivesAndPersistsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    // LVA-008: swallow the instrumentation activity-destroy nav-teardown ISE
    // (not a user-path crash) so the toggle assertions decide pass/fail.
    @get:Rule(order = 2)
    val lenientTeardown = LenientTeardownRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun syncToggle_click_doesNotCrash_flipsCheckedState_andPersists() {
        hiltRule.inject()

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

        // ProviderConfig has rendered when "Sync this provider" is present.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Sync this provider").fetchSemanticsNodes().isNotEmpty()
        }

        // The Switch is the first toggleable node on this screen (the Sync
        // section is rendered above the Anonymous section). Capture its
        // pre-click checked state from the rendered UI.
        val toggleable = { composeRule.onAllNodes(isToggleable()).onFirst() }
        val wasOn = runCatching { toggleable().assertIsOn() }.isSuccess

        // --- THE CLICK that used to crash the process ---
        toggleable().performClick()
        composeRule.waitForIdle()

        // (a) NO-CRASH (load-bearing). If ToggleSync raised
        //     SerializationException the activity would be gone and the
        //     "Sync this provider" Body would no longer be in the tree.
        //     Re-finding it proves the process survived the click.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Sync this provider").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Sync this provider").assertIsDisplayed()

        // (b) STATE FLIP — the rendered Switch shows the opposite state,
        //     proving the handler ran AND the persisted value re-bound
        //     into state.syncEnabled via observeAll(). Wait for the
        //     observe-flow to settle the new value.
        if (wasOn) {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onAllNodes(isToggleable()).onFirst().assertIsOff()
                }.isSuccess
            }
            toggleable().assertIsOff()
        } else {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onAllNodes(isToggleable()).onFirst().assertIsOn()
                }.isSuccess
            }
            toggleable().assertIsOn()
        }

        // (c) PERSISTS — a second read after a settle window shows the new
        //     state still holds (not a transient snap-back).
        composeRule.waitForIdle()
        if (wasOn) toggleable().assertIsOff() else toggleable().assertIsOn()
    }
}
