/*
 * SP-3a Phase 5 Challenge Test C1 — App launch and tracker selection.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.9. Compose UI test that drives the same surfaces a real user
 * touches when they open the app for the first time after upgrading to
 * Lava 1.2.0 and switch the active tracker.
 *
 * STATUS (updated SP-3a Step 6, 2026-04-30): NOW RUNNABLE. The :app module
 * has connectedAndroidTest infrastructure wired:
 *   - lava.app.LavaHiltTestRunner installs HiltTestApplication.
 *   - androidx.compose.ui.test.junit4 is on the androidTest classpath.
 *   - hilt-android-testing + kspAndroidTest(hilt-compiler) generate the
 *     test component graph.
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest"
 *
 * Source-only verification (no device required, runs in pre-push gate):
 *   ./gradlew :app:compileDebugAndroidTestKotlin
 *
 * The Sixth Law clause 5 real-device gate is still operator-attested via
 * .lava-ci-evidence/<TAG>/real-device-verification.md (Task 5.22).
 *
 * FALSIFIABILITY REHEARSAL (operator-executed, see evidence file):
 *
 *   1. In core/tracker/client/src/main/kotlin/lava/tracker/client/registry/
 *      RuTorRegistrationModule.kt comment out the @IntoSet line that
 *      contributes the RuTor factory to the multi-binding set.
 *   2. Re-run this Challenge Test on a real device (./gradlew
 *      :app:connectedDebugAndroidTest --tests
 *      lava.app.challenges.C1_AppLaunchAndTrackerSelectionTest).
 *   3. Expected failure: assertion 'two trackers listed' fires; only
 *      RuTracker shows up in the Settings → Trackers list.
 *   4. Revert the comment; re-run; test passes.
 *
 * Capture the rehearsal in
 * .lava-ci-evidence/sp3a-challenges/C1-<sha>.json with
 * { mutation, observed_failure_message, reverted, attestation_screenshot }.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge01AppLaunchAndTrackerSelectionTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunches_navigateToTrackerSettings_bothTrackersListed_canSelectRuTracker() {
        // Arrange — Hilt + MainActivity start; bottom-nav is on Search by default.
        hiltRule.inject()

        // Act — open the menu (Settings entry point), tap "Trackers".
        composeRule.onNodeWithText("Menu").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Trackers").performClick()

        // Assert — two trackers listed (primary user-visible state per
        // Sixth Law clause 3). If this assertion fires, the registry
        // multi-binding lost a factory (rehearsal mutation: drop @IntoSet).
        composeRule.onNodeWithText("RuTracker").assertIsDisplayed()
        composeRule.onNodeWithText("RuTor").assertIsDisplayed()

        // Act — tap RuTracker to make it active.
        composeRule.onNodeWithText("RuTracker").performClick()

        // Assert — the active-state badge "(active)" renders next to RuTracker.
        composeRule.onNodeWithText("RuTracker (active)").assertIsDisplayed()
    }
}
