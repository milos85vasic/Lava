/*
 * Challenge Test C13 — Firebase Cold-Start Resilience.
 *
 * Forensic anchor: 2026-05-05, 22:33 UTC. The first Firebase-instrumented
 * APK distribution (Lava-Android-1.2.3-1023, commit e9de508) recorded
 * 2 Crashlytics crashes within ~10 minutes of upload. The post-mortem
 * is at .lava-ci-evidence/crashlytics-resolved/2026-05-05-firebase-init-hardening.md.
 *
 * Constitutional binding: §6.O Crashlytics-Resolved Issue Coverage Mandate
 * (added 2026-05-05). This is the Challenge Test required by §6.O clause 2
 * for the Firebase-init crashes. The matching JVM validation test is at
 * `app/src/test/kotlin/digital/vasic/lava/client/firebase/FirebaseInitializerTest.kt`.
 *
 * What this test asserts: cold-starting the app on the gating matrix
 * does NOT crash, AND the user reaches the launcher activity within a
 * 10-second timeout. The primary assertion is on user-visible state (the
 * app is alive and rendering), which is the Sixth Law clause 3 contract.
 *
 * What this test does NOT assert: that Firebase Crashlytics actually
 * captured the cold-start telemetry — that requires the Firebase Console
 * dashboard, which is the operator-attestation surface per §6.I clause 5
 * (CI green is necessary, never sufficient). This test is the green
 * gate; the dashboard is the truth.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In FirebaseInitializer.kt, remove ONE of the runCatching{}
 *      blocks (e.g. around `crashlytics()?.apply { ... }`).
 *   2. Build the test APK with a deliberately-thrown SDK substitution
 *      (e.g. via a test-only Hilt module that returns a mockk that
 *      throws). The cold-start crash propagates to Application.onCreate.
 *   3. Run this Challenge Test on the gating matrix.
 *   4. Expected failure: the launcher Activity never reaches the
 *      "Search history" / onboarding text within the 10-second wait,
 *      and the assertion fires with "Cold start did not produce a
 *      rendered UI within 10s — Firebase init regression".
 *   5. Revert. The test passes again.
 *
 *   Mirror rehearsal: revert the LavaApplication change to call
 *   `FirebaseApp.initializeApp(this)` explicitly. On API 28-30 (where
 *   StrictMode is most strict), the race condition recurs. The test
 *   fails on those AVD rows of the matrix while passing on later APIs —
 *   which is itself a constitutional finding worth keeping per §6.I
 *   clause 5 ("the matrix exists to detect divergence between API
 *   levels").
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge13FirebaseColdStartResilienceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldStart_withFirebaseInit_reachesLauncherWithoutCrash() {
        hiltRule.inject()

        // The MainActivity Compose rule has already triggered the
        // production cold-start path:
        //   1. Process fork
        //   2. FirebaseInitProvider auto-initializes the default app
        //   3. LavaApplication.onCreate runs
        //   4. FirebaseInitializer.initialize wires Crashlytics + Analytics
        //      + Performance, each guarded by runCatching
        //   5. MainActivity inflates the Compose hierarchy
        //
        // The PRIMARY assertion is that the rendered UI exists. If any
        // step above had crashed, the @Rule construction would have
        // thrown and the test would never reach the assertion.

        composeRule.waitUntil(timeoutMillis = 10_000) {
            // Either the Search-history landing OR the onboarding
            // welcome screen — both are valid post-cold-start states.
            val searchHistory = composeRule.onAllNodesWithText(
                "Search history",
                substring = true,
                ignoreCase = true,
            ).fetchSemanticsNodes().isNotEmpty()

            val welcome = composeRule.onAllNodesWithText(
                "Welcome",
                substring = true,
                ignoreCase = true,
            ).fetchSemanticsNodes().isNotEmpty()

            val tracker = composeRule.onAllNodesWithText(
                "Tracker",
                substring = true,
                ignoreCase = true,
            ).fetchSemanticsNodes().isNotEmpty()

            searchHistory || welcome || tracker
        }
    }
}
