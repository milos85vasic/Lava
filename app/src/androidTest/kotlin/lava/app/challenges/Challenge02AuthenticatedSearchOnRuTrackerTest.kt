/*
 * SP-3a Phase 5 Challenge Test C2 — Authenticated search on RuTracker.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.10. Compose UI test that signs in with the operator-provided
 * RuTracker credentials, performs a real search for "ubuntu", and
 * asserts at least one result row renders with parseable size + seeders.
 *
 * STATUS (updated SP-3a Step 6, 2026-04-30): NOW RUNNABLE on a connected
 * device via
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge02AuthenticatedSearchOnRuTrackerTest"
 * Source-only compile is verified by the pre-push gate.
 * Operator real-device attestation per Task 5.22 still required for
 * release tagging (Sixth Law clause 5).
 *
 * CREDENTIALS: Per the SP-3a plan and operator memory, Test credentials
 * are user='nobody85perfect', password='ironman1985'. They MUST be
 * supplied at test runtime via -PrutrackerLogin=... -PrutrackerPassword=...
 * (read by the BuildConfig in androidTest), NOT hard-coded into the test.
 *
 * FALSIFIABILITY REHEARSAL (operator-executed):
 *
 *   1. In core/tracker/rutracker/src/main/kotlin/lava/tracker/rutracker/
 *      RuTrackerSearch.kt throw IllegalStateException("rehearsal") at
 *      the top of search().
 *   2. Re-run on real device.
 *   3. Expected failure: assertion 'result row visible' fires; the
 *      ViewModel surfaces the error and the SearchResult screen shows
 *      the error UI ("Search failed").
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.BuildConfig
import digital.vasic.lava.client.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge02AuthenticatedSearchOnRuTrackerTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun signIn_searchUbuntu_resultRowRendersWithSizeAndSeeders() {
        hiltRule.inject()

        // Constitutional clause 6.H: credentials come from .env at build time
        // via app/build.gradle.kts buildConfigField declarations. Empty
        // values mean the test environment has no .env (CI without secrets,
        // fresh clone). Skip rather than embedding placeholders.
        assumeTrue(
            "RUTRACKER_USERNAME/PASSWORD must be set in .env for this test",
            BuildConfig.RUTRACKER_USERNAME.isNotEmpty() &&
                BuildConfig.RUTRACKER_PASSWORD.isNotEmpty(),
        )

        // Step 1: Login. Drive the Login screen with operator-provided
        // credentials read from BuildConfig (instrumented runner sets
        // them from -P args).
        composeRule.onNodeWithText("Account").performClick()
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithContentDescription("Username field")
            .performTextInput(BuildConfig.RUTRACKER_USERNAME)
        composeRule.onNodeWithContentDescription("Password field")
            .performTextInput(BuildConfig.RUTRACKER_PASSWORD)
        composeRule.onNodeWithText("Login").performClick()

        // Wait for login round-trip and authenticated state.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Logged in").fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 2: Search for "ubuntu".
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()

        // Wait for the result list to render.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("seeders", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 3: Assert primary user-visible state (Sixth Law clause 3).
        composeRule.onNodeWithText("seeders", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("MB", substring = true)
            .assertIsDisplayed()
    }
}

// BuildConfigBridge removed 2026-05-04 — see app/build.gradle.kts
// `buildConfigField` declarations and the Seventh Law clause 6 incident
// record at .lava-ci-evidence/sixth-law-incidents/2026-05-04-bridge-credentials.json.
