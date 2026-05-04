/*
 * Challenge Test C2 — RuTracker authenticated Sign in flow (Phase 2.4 redesign 2026-05-04).
 *
 * Drives the REAL onboarding flow that a user takes when they pick
 * RuTracker.org with credentials. RuTracker is CAPTCHA_LOGIN +
 * supportsAnonymous=false — the user MUST enter username and password.
 *
 * This test does NOT use OnboardingBypassRule because the test's whole
 * purpose IS to exercise the credentialed sign-in flow. Empirically
 * verified flow on CZ_API34_Phone (API 34):
 *
 *   1. App launches at "Select Provider" screen
 *   2. User taps "RuTracker.org"
 *   3. Login screen renders Username + Password fields with a Sign in button
 *   4. User types RUTRACKER_USERNAME (from .env via BuildConfig)
 *   5. User types RUTRACKER_PASSWORD
 *   6. User taps Sign in (real network round-trip to rutracker.org)
 *   7. ProviderLoginViewModel.onSubmitClick:
 *      - sdk.login(...) → AuthResult.Success
 *      - credentialManager.setPassword(...)
 *      - sdk.switchTracker("rutracker")
 *      - authService.signalAuthorized(username)
 *      - LoginSideEffect.Success
 *   8. OnboardingScreen.onComplete → MainActivity flips
 *      showOnboarding=false → MobileNavigation mounts
 *   9. Bottom-tab nav (Search/Forum/Topics/Menu) appears
 *
 * The assertion target is step 9 — the user-visible main app.
 *
 * Forensic anchor (clauses 6.G/6.H/6.J/6.L):
 *
 *   The pre-Phase-2 version of this test asserted on text "Account",
 *   "Sign in", "Logged in" — none of which exist in the current UI;
 *   AND it shipped real credentials in a `private object BuildConfigBridge`
 *   placeholder (incident 2026-05-04-bridge-credentials.json). Both
 *   bluffs are now removed: this redesign asserts on the real onboarding
 *   flow's bottom-tab nav, and credentials are read from BuildConfig
 *   fields populated at build time from gitignored .env (clause 6.H).
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge02AuthenticatedSearchOnRuTrackerTest"
 *
 * The test SKIPS (assumeTrue) when RUTRACKER_USERNAME/PASSWORD are not
 * present in .env (CI without secrets, fresh clones). Skipped is visible
 * in the test report and does NOT count as PASS — the test will fail
 * the matrix-attestation gate if credentials are missing for any AVD.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In feature/login/.../ProviderLoginViewModel.kt comment out the
 *      `authService.signalAuthorized(name = state.usernameInput.value.text)`
 *      line in the AuthResult.Success branch.
 *   2. Re-run on the gating emulator with valid credentials.
 *   3. Expected failure: the Search tab still renders "Authorization
 *      required to search" because the legacy AuthService SharedFlow
 *      never receives the bridge signal; the test cannot find
 *      "Search history" within the timeout.
 *   4. Revert; re-run; test passes.
 *
 * Evidence at .lava-ci-evidence/sp3a-challenges/C2-2026-05-04-redesign.json.
 */
package lava.app.challenges

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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge02AuthenticatedSearchOnRuTrackerTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickRuTracker_enterCreds_signIn_reachesMainApp() {
        hiltRule.inject()

        // Constitutional clause 6.H: credentials come from .env at build
        // time via app/build.gradle.kts buildConfigField declarations.
        // Empty values mean the test environment has no .env (CI without
        // secrets, fresh clone). Skip rather than embedding placeholders.
        assumeTrue(
            "RUTRACKER_USERNAME/PASSWORD must be set in .env for this test",
            BuildConfig.RUTRACKER_USERNAME.isNotEmpty() &&
                BuildConfig.RUTRACKER_PASSWORD.isNotEmpty(),
        )

        // Step 1: app launches at "Select Provider" screen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Select Provider").fetchSemanticsNodes().isNotEmpty()
        }

        // Step 2: tap "RuTracker.org".
        composeRule.onNodeWithText("RuTracker.org").performClick()

        // Step 3: wait for the login form (Sign in button visible by
        // text — disabled until creds are entered, but findable).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
        }

        // Step 4: enter credentials. Username + Password fields are
        // identified by their content-descriptions (verified empirically
        // on CZ_API34_Phone).
        composeRule.onNodeWithContentDescription("Username")
            .performTextInput(BuildConfig.RUTRACKER_USERNAME)
        composeRule.onNodeWithContentDescription("Password")
            .performTextInput(BuildConfig.RUTRACKER_PASSWORD)

        // Step 5: tap Sign in. Real-network round-trip to rutracker.org
        // begins. Login may take several seconds; allow generous timeout.
        composeRule.onNodeWithText("Sign in").performClick()

        // Step 6: assert main-app bottom-tab nav appears (Sixth Law
        // clause 3 — primary user-visible state). The signalAuthorized
        // bridge (commit 45fd1ae) propagates the auth state to
        // SearchViewModel; the screen renders the "Search history"
        // empty state instead of "Authorization required".
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
