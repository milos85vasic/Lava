package lava.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import lava.designsystem.theme.LavaTheme
import lava.models.auth.Captcha
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5 (2026-06-11) dynamic provider discovery — auth-UI rendering test
 * (plan Task 5.2).
 *
 * Renders the REAL [ProviderLoginScreen] credential form under Robolectric for
 * each [lava.tracker.api.AuthType] a dynamically-discovered provider can declare
 * and asserts the form shows the RIGHT fields — the user-visible state (§6.AB
 * clause 1 / Sixth Law clause 3):
 *
 *   - NONE          → "Continue" (no credential fields)
 *   - FORM_LOGIN    → username + password (no API-key, no "Continue")
 *   - CAPTCHA_LOGIN → username + password + captcha (when a captcha challenge
 *                     is present — captcha rendering is challenge-driven, the
 *                     existing correct behaviour)
 *   - API_KEY       → single API-key field (no username/password, no "Continue")
 *
 * This is the load-bearing acceptance gate for the API_KEY rendering branch
 * added in Phase 5: before it existed, an API_KEY provider fell through to the
 * username/password form — a §6.AB rendering-correctness gap (the user was shown
 * the wrong fields and could not authenticate).
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / §6.AB clause 3), to record in the
 * Bluff-Audit stamp:
 *   Mutation: delete the `else if (provider.authType == "API_KEY")` branch in
 *             [ProviderCredentialForm] so API_KEY falls back to username/password.
 *   Observed: `api_key provider renders only the api-key field` FAILS —
 *             onNodeWithTag(ApiKeyInputFieldTestTag).assertIsDisplayed() throws
 *             "no nodes match" (the key field is gone; username/password render
 *             instead). The other three auth types stay green.
 *   Reverted: yes.
 *
 * Harness mirrors core/designsystem `A11yContentDescriptionTest` (Robolectric +
 * createComposeRule + ui-test-manifest host activity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProviderLoginAuthUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun providerItem(authType: String, supportsAnonymous: Boolean = false) =
        ProviderLoginItem(
            providerId = "p",
            displayName = "Test Provider",
            providerType = "Tracker",
            authType = authType,
            isAuthenticated = false,
            hasCredentials = false,
            supportsAnonymous = supportsAnonymous,
        )

    private fun renderForm(state: ProviderLoginState) {
        composeRule.setContent {
            LavaTheme {
                ProviderLoginScreen(state = state, onAction = {}, back = {})
            }
        }
    }

    @Test
    fun `none provider renders the Continue affordance and no credential fields`() {
        renderForm(
            ProviderLoginState(
                providers = listOf(providerItem("NONE")),
                selectedProviderId = "p",
            ),
        )

        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("This provider does not require authentication.").assertIsDisplayed()
        composeRule.onNodeWithTag(ApiKeyInputFieldTestTag).assertDoesNotExist()
        // The username label MUST NOT render for a no-auth provider.
        composeRule.onNodeWithText("Username").assertDoesNotExist()
    }

    @Test
    fun `form_login provider renders username and password fields`() {
        renderForm(
            ProviderLoginState(
                providers = listOf(providerItem("FORM_LOGIN")),
                selectedProviderId = "p",
            ),
        )

        composeRule.onNodeWithText("Username").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        // No no-auth "Continue", no API-key field.
        composeRule.onNodeWithText("Continue").assertDoesNotExist()
        composeRule.onNodeWithTag(ApiKeyInputFieldTestTag).assertDoesNotExist()
    }

    @Test
    fun `captcha_login provider renders username password and captcha fields when challenged`() {
        renderForm(
            ProviderLoginState(
                providers = listOf(providerItem("CAPTCHA_LOGIN")),
                selectedProviderId = "p",
                // A CAPTCHA_LOGIN provider in a captcha-challenge state — the
                // captcha field renders only when a challenge is present.
                captcha = Captcha(id = "sid", code = "code", url = "https://example/captcha.png"),
            ),
        )

        composeRule.onNodeWithText("Username").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        // The credential form is a verticalScroll Column; the captcha field sits
        // below username/password and is below the fold in the Robolectric
        // viewport. Scroll it into view, then assert it is genuinely displayed —
        // this proves a real, reachable captcha field (a user can scroll to and
        // see it), not merely a node present in the tree (§6.AB clause 1 / Sixth
        // Law clause 3 — user-visible state, not existence-only).
        composeRule.onNodeWithText("Captcha").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ApiKeyInputFieldTestTag).assertDoesNotExist()
    }

    @Test
    fun `api_key provider renders only the api-key field`() {
        renderForm(
            ProviderLoginState(
                providers = listOf(providerItem("API_KEY")),
                selectedProviderId = "p",
            ),
        )

        // PRIMARY: the API-key field IS present (located by stable tag).
        composeRule.onNodeWithTag(ApiKeyInputFieldTestTag).assertIsDisplayed()
        // And the username/password/no-auth affordances are NOT — an API_KEY
        // provider must not be shown the wrong fields (the bug this branch fixes).
        composeRule.onNodeWithText("Username").assertDoesNotExist()
        composeRule.onNodeWithText("Continue").assertDoesNotExist()
    }
}
