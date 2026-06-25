/*
 * Challenge Test C21 — Onboarding wizard: back press at Welcome closes the
 * app AND does NOT silently mark onboarding complete (Phase 3, 2026-05-08;
 * strengthened 2026-06-25 per §6.AB challenge-discrimination review
 * `.lava-ci-evidence/challenge-discrimination/2026-06-17-review.md` WEAK #9).
 *
 * Forensic anchor (1.2.20-1040, operator-reported): pressing back on the
 * first onboarding screen silently wrote `onboarding_complete = true` and
 * routed the user into a half-functional home screen with zero providers.
 *
 * The PRE-strengthening C21 only asserted the Welcome screen rendered — it
 * NEVER pressed back and NEVER asserted on close, so it would pass against
 * the exact gate-bypass it claims to guard (WEAK Tier-2). It is now a
 * behavioral gate that drives the real back-press and asserts on two
 * independent user-visible signals:
 *
 *   1. The Activity is finishing (lifecycle signal — symmetric with C28).
 *   2. The persisted `settings/onboarding_complete` preference stays FALSE
 *      (persistence signal — the DISTINCT angle C28 does not cover; C28's
 *      KDoc explicitly defers this to "covered indirectly by C29").
 *
 * Driving system back via `OnBackPressedDispatcher.onBackPressed()` is the
 * established pattern (see C28 / C24): `Espresso.pressBack()` does not
 * deliver to the Compose `BackHandler` reliably in instrumentation.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge21OnboardingBackPressClosesAppTest"
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In OnboardingViewModel.kt, revert the Welcome branch in onBackStep()
 *      to `postSideEffect(OnboardingSideEffect.Finish)` (the 1.2.20 bug),
 *      so back-from-Welcome routes to home and writes onboarding_complete.
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `assert(!onboardingComplete)` throws
 *      "onboarding_complete is TRUE after back-from-Welcome — the wizard
 *      silently marked onboarding complete (§6.AB gate-bypass, 1.2.20)"
 *      AND/OR the `assert(activity.isFinishing)` fails because the Activity
 *      transitioned to home instead of finishing.
 *   4. Restore the ExitApp branch; re-run; passes.
 */
package lava.app.challenges

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge21OnboardingBackPressClosesAppTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun backFromWelcome_closesApp_andDoesNotMarkOnboardingComplete() {
        hiltRule.inject()

        // Welcome screen renders on first launch (onboardingComplete=false).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertIsDisplayed()
        composeRule.onNodeWithText("Get Started").assertIsDisplayed()

        // Drive the real system back-press from the Welcome step.
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // Let the Activity process the ExitApp -> finishAffinity() path.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            var finishing = false
            composeRule.activityRule.scenario.onActivity { activity ->
                finishing = activity.isFinishing
            }
            finishing
        }

        // Lifecycle signal: the Activity MUST be finishing (NOT routed to home).
        composeRule.activityRule.scenario.onActivity { activity ->
            assert(activity.isFinishing) {
                "Activity is NOT finishing after back-from-Welcome — the wizard " +
                    "routed to home instead of closing (§6.AB gate-bypass, " +
                    "forensic anchor 1.2.20-1040)."
            }
        }

        // Persistence signal (DISTINCT from C28): back-from-Welcome MUST NOT
        // have written onboarding_complete=true. Read it back from the real
        // encrypted "settings" store the production PreferencesStorage uses.
        assert(!readOnboardingComplete()) {
            "onboarding_complete is TRUE after back-from-Welcome — the wizard " +
                "silently marked onboarding complete with zero providers " +
                "configured (§6.AB gate-bypass, forensic anchor 1.2.20-1040). " +
                "Back-from-Welcome must close the app WITHOUT completing onboarding."
        }
    }

    /**
     * Reads the production `settings/onboarding_complete` flag the same way
     * `PreferencesStorageImpl` writes it (key `onboarding_complete`, store
     * `settings`, EncryptedSharedPreferences AES256_SIV/AES256_GCM). Falls
     * back to plain SharedPreferences in environments without a usable
     * Android Keystore — identical to `ResetOnboardingPrefsRule`.
     */
    private fun readOnboardingComplete(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return try {
            val mainKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "settings",
                mainKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).getBoolean("onboarding_complete", false)
        } catch (_: Exception) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("onboarding_complete", false)
        }
    }
}
