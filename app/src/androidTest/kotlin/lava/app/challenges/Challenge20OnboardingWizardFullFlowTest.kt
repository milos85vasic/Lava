/*
 * Challenge Test C20 — Full onboarding wizard flow (Phase 3, 2026-05-08).
 *
 * Drives the multi-step onboarding wizard that a user encounters on first
 * launch: Welcome → Pick Providers → Configure → Summary → Main App.
 *
 * This test does NOT use OnboardingBypassRule because its purpose IS to
 * exercise the real onboarding flow end-to-end. Uses anonymous provider
 * (Internet Archive) to avoid credential dependency.
 *
 * Flow on a real device:
 *   1. App launches at "Welcome to Lava" with provider count
 *   2. Tap "Get Started" → "Pick your providers" screen
 *   3. Deselect all providers except Internet Archive
 *   4. Tap "Next" → "Configure Internet Archive" screen
 *   5. Tap "Continue" → connection test → auto-advance
 *   6. "All set!" summary shows Internet Archive as configured
 *   7. Tap "Start Exploring" → bottom-tab nav appears
 *
 * The assertion target is step 7 — the user-visible main app.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge20OnboardingWizardFullFlowTest"
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   1. In OnboardingViewModel.kt, comment out the
 *      `postSideEffect(OnboardingSideEffect.Finish)` line in `onFinish()`.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: "Search history" never appears within
 *      timeout because MainActivity never transitions from onboarding
 *      to main app.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge20OnboardingWizardFullFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcome_pickAnonProvider_configure_finish_reachesMainApp() {
        hiltRule.inject()

        // Step 1: Welcome screen
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertExists()
        composeRule.onNodeWithText("providers available").assertExists()
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: Pick Providers — only Internet Archive (anonymous, no creds)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        // Deselect all auth-required providers; keep only Internet Archive
        arrayOf("RuTracker", "RuTor", "Kinozal", "NNM-Club", "Project Gutenberg").forEach { name ->
            try {
                composeRule.onNodeWithText(name).performClick()
            } catch (_: AssertionError) {
                // Provider may not be in the list; skip gracefully
            }
        }
        // Verify "Internet Archive" is still visible
        composeRule.onNodeWithText("Internet Archive").assertExists()

        // Step 3: "Next" → Configure screen
        composeRule.onNodeWithText("Next").performClick()

        // Step 4: Configure — anonymous provider shows "This provider does not require credentials."
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure Internet Archive").fetchSemanticsNodes().isNotEmpty()
        }
        // Tap "Continue" → connection test → auto-advance
        composeRule.onNodeWithText("Continue").performClick()

        // Step 5: Summary screen
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All set!").assertExists()
        composeRule.onNodeWithText("Internet Archive").assertExists()

        // Step 6: "Start Exploring" → main app
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 7: Verify main app bottom-tab nav is visible (Sixth Law clause 3
        // primary user-visible state assertion).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
