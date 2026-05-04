/*
 * Challenge Test C10 — NNM-Club authenticated search (clause 6.G).
 *
 * NNM-Club is FORM_LOGIN. Test follows the same shape as C9 (Kinozal):
 * onboarding → pick provider → enter credentials → main app → search.
 *
 * Credentials come from .env via BuildConfig.NNMCLUB_USERNAME and
 * BuildConfig.NNMCLUB_PASSWORD (clause 6.H). The test skips when
 * credentials are not configured. Skipped is visible in the test
 * report and does NOT count as PASS — the provider stays verified=false
 * until a real run with real creds passes.
 *
 * STATUS: requires NnmclubDescriptor.verified=true to appear in the
 * onboarding list.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/nnmclub/.../NnmclubAuth.kt force the login
 *      result to AuthState.Unauthenticated regardless of credentials.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: "Logged in" indicator never appears; the
 *      waitUntil for the search bar times out.
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
import lava.tracker.nnmclub.NnmclubDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge10NnmclubAuthenticatedSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickNnmclub_login_searchUbuntu_resultRowVisible() {
        hiltRule.inject()

        assumeTrue(
            "NnmclubDescriptor.verified must be true for this test to apply (clause 6.G)",
            NnmclubDescriptor.verified,
        )
        assumeTrue(
            "NNMCLUB_USERNAME/PASSWORD must be set in .env for this test",
            BuildConfig.NNMCLUB_USERNAME.isNotEmpty() &&
                BuildConfig.NNMCLUB_PASSWORD.isNotEmpty(),
        )

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("NNM", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("NNM", substring = true, ignoreCase = true)
            .performClick()

        composeRule.onNodeWithContentDescription("Username field")
            .performTextInput(BuildConfig.NNMCLUB_USERNAME)
        composeRule.onNodeWithContentDescription("Password field")
            .performTextInput(BuildConfig.NNMCLUB_PASSWORD)
        composeRule.onNodeWithText("Login").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Search", substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("seeders", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("seeders", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }
}
