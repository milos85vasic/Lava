/*
 * Challenge Test C24 — Onboarding back navigation works at every step
 * (Issue: 2026-05-14, "We do not have back navigation in Onboarding").
 *
 * The pre-fix shape of OnboardingScreen.kt:
 *   BackHandler(enabled = state.step == OnboardingStep.Welcome) { ... }
 * inverted the predicate — back was intercepted only on Welcome (where
 * it should fall through to exit) and ignored on Providers / Configure /
 * Summary (where the user actively needs to go back). The VM's
 * onBackStep() was correctly designed but never reached. Every transition
 * the user could see was broken.
 *
 * This Challenge Test drives the activity's OnBackPressedDispatcher
 * (which fans out to the Compose-registered BackHandler) and asserts
 * that the rendered screen transitions to the prior step. Per the C21
 * note, Espresso.pressBack() does not deliver to Compose BackHandler
 * reliably; using onBackPressedDispatcher.onBackPressed() does.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge24OnboardingBackNavigationTest"
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   1. In OnboardingScreen.kt, restore the inverted predicate:
 *      `BackHandler(enabled = state.step == OnboardingStep.Welcome)`.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: backFromProviders_returnsToWelcome times out
 *      because back is not intercepted; the activity finishes instead
 *      and the "Welcome to Lava" text never re-appears.
 *   4. Revert; re-run; test passes.
 *
 *   Secondary mutation (catches the §6.J Summary gap):
 *   1. In OnboardingViewModel.onBackStep(), restore the Summary branch
 *      to `OnboardingStep.Summary -> { /* ignore */ }`.
 *   2. Re-run.
 *   3. Expected failure: backFromSummary_returnsToConfigure times out
 *      because the Summary screen does not transition.
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
class Challenge24OnboardingBackNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun backFromProviders_returnsToWelcome() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertIsDisplayed()
    }

    @Test
    fun backFromConfigure_returnsToProviders() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        // Deselect everything except Internet Archive (anonymous, no creds needed).
        arrayOf("RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club", "Project Gutenberg").forEach { name ->
            val nodes = composeRule.onAllNodesWithText(name).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) composeRule.onNodeWithText(name).performClick()
        }
        composeRule.onNodeWithText("Next").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Pick your providers").assertIsDisplayed()
    }

    @Test
    fun backFromSummary_returnsToConfigure() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        arrayOf("RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club", "Project Gutenberg").forEach { name ->
            val nodes = composeRule.onAllNodesWithText(name).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) composeRule.onNodeWithText(name).performClick()
        }
        composeRule.onNodeWithText("Next").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Configure", substring = true).assertIsDisplayed()
    }
}
