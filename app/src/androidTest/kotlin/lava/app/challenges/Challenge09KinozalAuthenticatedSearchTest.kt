/*
 * Challenge Test C9 — Kinozal authenticated search (clause 6.G).
 *
 * Per CLAUDE.md §6.G clause 4: a provider that ships verified=true MUST
 * have a Challenge Test exercising the real production stack from the
 * user's first tap to a successful outcome on the real upstream service.
 *
 * Flow:
 *   onboarding → pick "kinozal" → enter username/password → login round-trip
 *   → main app → search "ubuntu" → result row visible.
 *
 * Credentials come from .env at build time via BuildConfig.KINOZAL_USERNAME
 * and BuildConfig.KINOZAL_PASSWORD (clause 6.H). The test skips when
 * credentials are not configured (e.g. fresh clones or CI without secrets);
 * skipped is visible in the test report and does NOT count as PASS.
 *
 * Network reality: kinozal.tv is sometimes geofenced (CIS-only). A test
 * environment without route to the upstream produces a deterministic
 * timeout failure (waitUntil exceeds 30s) — not a green-with-broken bluff.
 *
 * STATUS: requires KinozalDescriptor.verified=true to make the provider
 * appear in the onboarding list. Per the run protocol the operator (or
 * agent rehearsing the gate) flips verified=true in the same change as
 * adding this test, runs the test, and either keeps the flip (test
 * passes) or reverts it (test fails).
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/kinozal/src/main/kotlin/lava/tracker/kinozal/
 *      KinozalAuth.kt force the login result to AuthState.Unauthenticated
 *      (or throw) regardless of credentials.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: "Logged in" indicator never appears; the
 *      waitUntil at line ~70 times out.
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
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.BuildConfig
import digital.vasic.lava.client.MainActivity
import lava.tracker.kinozal.KinozalDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge09KinozalAuthenticatedSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickKinozal_login_searchUbuntu_resultRowVisible() {
        hiltRule.inject()

        assumeTrue(
            "KinozalDescriptor.verified must be true for this test to apply (clause 6.G)",
            KinozalDescriptor.verified,
        )
        assumeTrue(
            "KINOZAL_USERNAME/PASSWORD must be set in .env for this test",
            BuildConfig.KINOZAL_USERNAME.isNotEmpty() &&
                BuildConfig.KINOZAL_PASSWORD.isNotEmpty(),
        )

        // Step 1: provider list (onboarding/login). Kinozal must be
        // present iff KinozalDescriptor.verified=true.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("kinozal", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("kinozal", substring = true, ignoreCase = true)
            .performClick()

        // Step 2: enter credentials and tap login.
        composeRule.onNodeWithContentDescription("Username field")
            .performTextInput(BuildConfig.KINOZAL_USERNAME)
        composeRule.onNodeWithContentDescription("Password field")
            .performTextInput(BuildConfig.KINOZAL_PASSWORD)
        composeRule.onNodeWithText("Login").performClick()

        // Step 3: wait for login round-trip and main app to render.
        // Auth-success surface = the search bar of the main app.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Search", substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 4: search "ubuntu" against the real kinozal.tv.
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()

        // Step 5: assert primary user-visible state (Sixth Law clause 3).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("seeders", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("seeders", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }
}
