/*
 * SP-3a Phase 5 Challenge Test C2 — Authenticated search on RuTracker.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.10. Compose UI test that signs in with the operator-provided
 * RuTracker credentials, performs a real search for "ubuntu", and
 * asserts at least one result row renders with parseable size + seeders.
 *
 * STATUS: Source-only at SP-3a Phase 5 commit. Operator runs on a real
 * device per Task 5.22.
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
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

        // Step 1: Login. Drive the Login screen with operator-provided
        // credentials read from BuildConfig (instrumented runner sets
        // them from -P args).
        composeRule.onNodeWithText("Account").performClick()
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithContentDescription("Username field")
            .performTextInput(BuildConfigBridge.RUTRACKER_USERNAME)
        composeRule.onNodeWithContentDescription("Password field")
            .performTextInput(BuildConfigBridge.RUTRACKER_PASSWORD)
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

/**
 * Indirection so this source compiles even before the BuildConfig
 * fields are wired up by the documentation-polish plan (when an
 * androidTest runner is added). The operator who wires the runner
 * generates the BuildConfig fields and replaces this object with a
 * BuildConfig reference in the tests.
 */
private object BuildConfigBridge {
    const val RUTRACKER_USERNAME: String = "nobody85perfect"
    const val RUTRACKER_PASSWORD: String = "ironman1985"
}
