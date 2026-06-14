/*
 * Challenge Test C42 — Onboarding provider Configure-step password masking +
 * eye-toggle rendered-UI contract.
 *
 * Feature anchor (landed on master @bb357da8): the provider Configure step's
 * password field now masks characters by default and exposes a trailing eye
 * control (tagged `PasswordVisibilityToggleTestTag` = "password_visibility_toggle")
 * that reveals / re-hides the typed password. In `ConfigureStep.kt`:
 *   - `visualTransformation = if (passwordVisible) VisualTransformation.None
 *                            else PasswordVisualTransformation()`
 *   - tapping the eye flips `passwordVisible`.
 *
 * Why this is honest under §6.J / §6.AB:
 * - The Composable rendered is the SAME production `lava.onboarding.steps.ConfigureStep`
 *   the production `OnboardingScreen` instantiates — no mock screen, no fake step.
 * - The masking logic under test (PasswordVisualTransformation by default; eye
 *   toggles to VisualTransformation.None) lives INSIDE the production composable,
 *   so this test exercises it directly through the real eye control.
 * - The PRIMARY assertion is on the user-visible RENDERED password text: while
 *   masked, the plaintext "secret123" is NOT displayed (the field renders bullet
 *   characters); after tapping the eye, the plaintext "secret123" IS displayed.
 *   Per §6.AB rendering-correctness + Sixth Law clause 3 — not a callback count.
 *
 * Note on the semantic asserted: Compose exposes the password field's RENDERED
 * (visual-transformation-applied) string via the EditableText semantics property.
 * When masked, that rendered string is the bullet run "•••••••••" — NOT
 * "secret123". `onNodeWithText("secret123")` therefore finds NO node while masked
 * and DOES find the field after the eye reveals it. We assert both directions on
 * the same production field.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In ConfigureStep.kt make the password field NEVER mask: change
 *      `visualTransformation = if (passwordVisible) VisualTransformation.None
 *      else PasswordVisualTransformation()` to a constant
 *      `visualTransformation = VisualTransformation.None` (the field renders +
 *      accepts input, but leaks the plaintext from the start — the §6.AB
 *      non-crashing class).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `passwordIsMaskedByDefault_andEyeRevealsPlaintext` fails
 *      at the FIRST masked assertion — onNodeWithText("secret123") now finds the
 *      node while it should be masked, so assertDoesNotExist throws
 *      "Failed: assertDoesNotExist ... found 1 node(s) ... 'secret123'".
 *   4. Revert; re-run; passes.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge42OnboardingPasswordMaskingTest"
 */
package lava.app.challenges

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import lava.designsystem.theme.LavaTheme
import lava.onboarding.ProviderConfigState
import lava.onboarding.steps.ConfigureStep
import lava.onboarding.steps.PasswordVisibilityToggleTestTag
import lava.sdk.api.MirrorUrl
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
class Challenge42OnboardingPasswordMaskingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val formLoginProvider: TrackerDescriptor =
        object : TrackerDescriptor {
            override val trackerId: String = "formtracker"
            override val displayName: String = "Form Tracker"
            override val baseUrls: List<MirrorUrl> =
                listOf(MirrorUrl(url = "https://formtracker.example", isPrimary = true))
            override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = AuthType.FORM_LOGIN
            override val encoding: String = "UTF-8"
            override val expectedHealthMarker: String = "marker"
            override val verified: Boolean = true
            override val apiSupported: Boolean = true
            // supportsAnonymous defaults to false → no anonymous switch; the
            // username + masked-password credential fields are always shown.
        }

    private val knownPassword = "secret123"

    // CHALLENGE: the password field masks the typed plaintext by default; tapping
    // the eye reveals it, tapping again re-hides it. Primary assertion on the
    // rendered password text (user-visible), across the full mask→reveal→mask cycle.
    @Test
    fun passwordIsMaskedByDefault_andEyeRevealsPlaintext() {
        composeRule.setContent {
            // Real hoisted ProviderConfigState so onPasswordChanged actually
            // updates the production binding `value = config.password`. This is
            // the same state object OnboardingViewModel feeds ConfigureStep.
            var config by mutableStateOf(
                ProviderConfigState(providerId = formLoginProvider.trackerId),
            )
            LavaTheme {
                ConfigureStep(
                    provider = formLoginProvider,
                    config = config,
                    isRunning = false,
                    onUsernameChanged = { config = config.copy(username = it) },
                    onPasswordChanged = { config = config.copy(password = it) },
                    onToggleAnonymous = { config = config.copy(useAnonymous = it) },
                    onTestAndContinue = {},
                )
            }
        }

        // Type a known password into the Password field. The field is addressed
        // by its label "Password" (OutlinedTextField surfaces the label in the
        // node), the same way C02 drives the real login Password field.
        composeRule.onNodeWithText("Password").performTextInput(knownPassword)
        composeRule.waitForIdle()

        // MASKED state: the plaintext MUST NOT be displayed. The field renders
        // bullet characters; onNodeWithText("secret123") finds nothing.
        composeRule.onNodeWithText(knownPassword).assertDoesNotExist()

        // Tap the eye toggle → reveals plaintext.
        composeRule.onNodeWithTag(PasswordVisibilityToggleTestTag).performClick()
        composeRule.waitForIdle()

        // REVEALED state: the plaintext IS now displayed in the field.
        composeRule.onNodeWithText(knownPassword).assertIsDisplayed()

        // Tap again → re-hides; plaintext gone once more (full round-trip).
        composeRule.onNodeWithTag(PasswordVisibilityToggleTestTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(knownPassword).assertDoesNotExist()
    }
}
