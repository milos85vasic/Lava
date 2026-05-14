/*
 * Challenge Test C28 — Back-from-Welcome closes the app entirely
 * (NOT marks onboarding complete and routes to a half-functional home).
 *
 * §6.AB onboarding-gate enforcement, forensic anchor: 2026-05-14
 * operator-reported gate-bypass on Lava-Android-1.2.20-1040 — pressing
 * back on the first onboarding screen silently marked
 * `onboardingComplete = true` and dumped the user into the home screen
 * with zero providers configured.
 *
 * This test verifies that on a fresh install (onboardingComplete=false):
 *   1. App opens to the Welcome screen
 *   2. Pressing back from Welcome closes the Activity (isFinishing=true)
 *   3. `onboardingComplete` preference remains FALSE — re-launch would
 *      re-enter onboarding (this part is covered indirectly by C29)
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge28OnboardingWelcomeBackClosesAppTest"
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In OnboardingViewModel.kt, revert the Welcome branch in
 *      onBackStep() to `postSideEffect(OnboardingSideEffect.Finish)`.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: assertActivityFinishing fails because the
 *      Activity is NOT finishing (it transitioned to home screen,
 *      stays alive). This is the §6.AB gate-bypass failure mode.
 *   4. Restore ExitApp; re-run; passes.
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
class Challenge28OnboardingWelcomeBackClosesAppTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun backFromWelcome_closesApp_doesNotRouteToHome() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertIsDisplayed()

        // Drive system back via the Activity's OnBackPressedDispatcher
        // (per the C21 / C24 note that Espresso.pressBack() does not
        // deliver to Compose BackHandler reliably).
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // Allow the Activity to process the finish() call.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            var finishing = false
            composeRule.activityRule.scenario.onActivity { activity ->
                finishing = activity.isFinishing
            }
            finishing
        }

        // Discrimination assertion: the Activity MUST be finishing. The
        // pre-fix gate-bypass would have routed to home (Activity stays
        // alive, transitions content) — that's the failure mode.
        composeRule.activityRule.scenario.onActivity { activity ->
            assert(activity.isFinishing) {
                "Activity is NOT finishing after back-from-Welcome — this " +
                    "is the §6.AB onboarding-gate-bypass failure mode " +
                    "(forensic anchor: 1.2.20-1040 reported by operator). " +
                    "ExitApp side effect is supposed to call finishAffinity()."
            }
        }
    }
}
