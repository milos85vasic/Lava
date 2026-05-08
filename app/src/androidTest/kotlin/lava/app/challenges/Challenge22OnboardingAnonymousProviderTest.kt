/*
 * Challenge Test C22 — Anonymous provider auto-advances through onboarding
 * (Phase 3, 2026-05-08).
 *
 * Picks an AuthType.NONE provider (Internet Archive) in the onboarding
 * wizard and verifies the connection test auto-advances to Summary without
 * a credential form. This is the anonymous-provider path from the design
 * spec: "shows provider name + 'Continue as Anonymous' button. Tapping it
 * runs health check, on success advances."
 *
 * This test exercises the configure-step path for anonymous providers
 * specifically — no username/password fields should appear, and tapping
 * "Continue" should trigger checkAuth() and advance.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge22OnboardingAnonymousProviderTest"
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   1. In OnboardingViewModel.kt, in onTestAndContinue(), change the
 *      AuthType.NONE branch to set error "Simulated failure" always.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: "Simulated failure" text appears in the
 *      Configure screen and the test times out waiting for "All set!".
 *   4. Revert; re-run; test passes.
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
import lava.app.ResetOnboardingPrefsRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge22OnboardingAnonymousProviderTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickInternetArchive_continue_autoAdvancesToSummary() {
        hiltRule.inject()

        // Step 1: Welcome → "Get Started"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: Pick Providers — deselect all except Internet Archive
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        arrayOf("RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club", "Project Gutenberg").forEach { name ->
            try {
                composeRule.onNodeWithText(name).performClick()
            } catch (_: AssertionError) {
                // Provider may not be in the list; skip gracefully
            }
        }
        composeRule.onNodeWithText("Internet Archive").assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()

        // Step 3: Configure — anonymous provider path
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure Internet Archive").fetchSemanticsNodes().isNotEmpty()
        }
        // Anonymous providers should NOT show credential fields
        composeRule.onNodeWithText("This provider does not require credentials.").assertIsDisplayed()
        // Tap "Continue" — triggers checkAuth() → auto-advance on success
        composeRule.onNodeWithText("Continue").performClick()

        // Step 4: Summary screen reached — the load-bearing acceptance gate
        // (Sixth Law clause 3): the user observes "All set!" with Internet Archive
        // marked as configured.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All set!").assertIsDisplayed()
        composeRule.onNodeWithText("Internet Archive").assertIsDisplayed()
    }
}
