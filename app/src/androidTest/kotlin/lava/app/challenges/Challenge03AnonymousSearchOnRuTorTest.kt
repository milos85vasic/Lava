/*
 * Challenge Test C3 — RuTor anonymous Continue flow (Phase 2.3 redesign 2026-05-04).
 *
 * Drives the REAL onboarding flow that a user takes when they open the
 * app for the first time AND pick "RuTor.info" with the Anonymous Access
 * toggle on. RuTor is FORM_LOGIN + supportsAnonymous=true (decision
 * 7b-ii) — anonymous browse without credentials.
 *
 * This test does NOT use OnboardingBypassRule because the test's whole
 * purpose IS to exercise the onboarding flow. After Phase 2.1 / 2.2 +
 * the Phase 1.5 ProviderLoginScreen UI alignment, the flow:
 *
 *   1. App launches at Onboarding's "Select Provider" screen
 *   2. User taps the Anonymous Access switch (top of the list)
 *   3. User taps "RuTor.info"
 *   4. Login screen renders Continue button (because supportsAnonymous=true
 *      AND state.anonymousMode=true after the toggle)
 *   5. User taps Continue
 *   6. ProviderLoginViewModel.onSubmitClick takes the anonymous bypass:
 *      sdk.switchTracker("rutor") + authService.signalAuthorized(...)
 *      + LoginSideEffect.Success
 *   7. OnboardingScreen's outer wrapper invokes onComplete
 *   8. MainActivity flips showOnboarding=false, MobileNavigation mounts
 *   9. Bottom-tab nav (Search/Forum/Topics/Menu) appears
 *
 * The assertion target is step 9 — the user-visible main app.
 *
 * Forensic anchor (clauses 6.G/6.J/6.L):
 *
 *   The pre-Phase-2 version of this test asserted on text "Settings",
 *   "Trackers", "RuTor (active)" — none of which exist in the current
 *   UI. C3 was a bluff alongside C1-C8. This redesign asserts on real
 *   user-visible state from a real run on CZ_API34_Phone API 34.
 *
 *   The flow ALSO exercises the Phase-1.5 + ProviderLoginScreen UI fix
 *   (commit 590a576 + this commit) — without supportsAnonymous gating,
 *   the Continue button would have rendered for non-anonymous-capable
 *   providers too.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge03AnonymousSearchOnRuTorTest"
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In core/tracker/rutor/src/main/kotlin/lava/tracker/rutor/
 *      RuTorDescriptor.kt revert `override val supportsAnonymous: Boolean = true`
 *      back to the default (false).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: ProviderLoginScreen renders the Username/
 *      Password form instead of the Continue button (per the Phase 1.5
 *      UI gate); the test cannot find "Continue" within the timeout.
 *   4. Revert; re-run; test passes.
 *
 * Evidence at .lava-ci-evidence/sp3a-challenges/C3-2026-05-04-redesign.json.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.login.AnonymousAccessSwitchTestTag
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge03AnonymousSearchOnRuTorTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun toggleAnonymous_pickRuTor_continue_reachesMainApp() {
        hiltRule.inject()

        // Step 1: app starts at "Select Provider" screen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Select Provider").fetchSemanticsNodes().isNotEmpty()
        }

        // Step 2: toggle the Anonymous Access switch via test tag.
        composeRule.onNodeWithTag(AnonymousAccessSwitchTestTag).performClick()

        // Step 3: tap RuTor.info. The Phase 1.5 UI gate sees
        // supportsAnonymous=true && anonymousMode=true → renders the
        // Continue button on the login screen.
        composeRule.onNodeWithText("RuTor.info").performClick()

        // Step 4: wait for the Continue button on the login screen.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Step 5: assert main-app bottom-tab nav appears (Sixth Law
        // clause 3 — primary user-visible state). The navigation runs
        // through OnboardingScreen.onComplete → MainActivity flips
        // showOnboarding=false → MobileNavigation mounts.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("There will be list of recent searches")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }
}
