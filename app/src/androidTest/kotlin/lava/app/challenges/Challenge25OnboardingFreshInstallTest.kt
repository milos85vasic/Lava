/*
 * Challenge Test C25 — Onboarding wizard renders on clean install.
 *
 * Forensic anchor (2026-05-12, operator-reported): on clean install
 * the onboarding wizard did NOT appear; users landed directly in
 * MainScreen with no providers configured. Root cause:
 * MainActivity's `showOnboarding` defaulted to `false` and was
 * loaded asynchronously, while `splashScreen.setKeepOnScreenCondition`
 * only waited for theme — not the onboarding flag. The first frame
 * could render MainScreen before showOnboarding was set to true.
 *
 * Fix: `showOnboarding` made nullable; keep-splash extended to wait
 * for both theme AND onboarding-status; `if (showOnboarding == true)`
 * shows the wizard, `else if (showOnboarding == false)` shows main.
 *
 * This test verifies that on a fresh install (ResetOnboardingPrefsRule
 * clears the prefs), the user lands on the Welcome screen of the
 * onboarding wizard, NOT on the MainScreen.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In MainActivity.kt revert `if (showOnboarding == true)` back to
 *      `if (showOnboarding)` and `var showOnboarding: Boolean? = null`
 *      back to `var showOnboarding = false`.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: the test sees MainScreen (no "Welcome to Lava"
 *      text within 15s) → waitUntil times out.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge25OnboardingFreshInstallTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cleanInstall_landsOnOnboardingWelcomeScreen() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Welcome to Lava", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Get Started", ignoreCase = true).assertIsDisplayed()
    }
}
