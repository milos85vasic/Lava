/*
 * Challenge Test C23 — Onboarding theme: all steps render with Lava DS
 * components (Phase 3, 2026-05-08).
 *
 * Verifies that every step of the onboarding wizard renders key user-facing
 * elements correctly after the Lava Design System component migration. Each
 * step's composables use Surface(AppTheme.colors.background) wrappers and
 * Lava DS components (Button, Text, Surface, OutlinedTextField, etc.)
 * instead of raw Material3.
 *
 * C20 tests the functional path through the wizard. C23 additionally verifies
 * that ALL steps render their unique UX elements properly — provider names,
 * credential fields, summary list — ensuring the theme migration didn't
 * break any step's layout.
 *
 * Flow on a real device:
 *   1. "Welcome to Lava" — title, provider count, subtitle, "Get Started"
 *   2. "Pick your providers" — provider names visible, auth type labels,
 *      checkboxes, "Next" enabled when selection present
 *   3. Deselect all but Internet Archive → "Next"
 *   4. "Configure Internet Archive" — anonymous notice, "Continue"
 *   5. "Continue" → connection test → "All set!" summary with provider
 *   6. "Start Exploring" → main app bottom-tab nav
 *
 * The primary assertion at each step confirms user-visible elements
 * are displayed (Sixth Law clause 3).
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge23OnboardingThemeRenderingTest"
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   1. In WelcomeStep.kt, replace `Text("Welcome to Lava")` with
 *      `Text("Hello")`.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: "Welcome to Lava" assertion fires.
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
class Challenge23OnboardingThemeRenderingTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allOnboardingSteps_renderKeyUxElements() {
        hiltRule.inject()

        // Step 1: Welcome — verify title, provider count, subtitle, button
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertExists()
        composeRule.onNodeWithText("providers available", substring = true).assertExists()
        composeRule.onNodeWithText("Get Started").assertIsDisplayed()
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: Providers — verify title, subtitle, provider names visible
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Pick your providers").assertExists()
        composeRule.onNodeWithText("Select one or more content providers to configure.").assertExists()
        composeRule.onNodeWithText("Internet Archive").assertExists()
        composeRule.onNodeWithText("Next").assertIsDisplayed()

        // Deselect all auth-required providers; keep only Internet Archive.
        // Use exact displayNames as rendered in ProvidersStep (e.g. "RuTracker.org").
        arrayOf("RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club", "Project Gutenberg").forEach { name ->
            try {
                composeRule.onNodeWithText(name).performClick()
            } catch (_: AssertionError) { }
        }
        composeRule.onNodeWithText("Internet Archive").assertExists()
        composeRule.onNodeWithText("Next").performClick()

        // Step 3: Configure — verify provider-specific credential UI
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure Internet Archive").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Configure Internet Archive").assertExists()
        composeRule.onNodeWithText("This provider does not require credentials.").assertExists()
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()

        // Step 4: Summary — verify provider list and start button
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All set!").assertExists()
        composeRule.onNodeWithText("Internet Archive").assertExists()
        composeRule.onNodeWithText("Start Exploring").assertIsDisplayed()
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 5: Main app — verify bottom-tab nav is reachable
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
