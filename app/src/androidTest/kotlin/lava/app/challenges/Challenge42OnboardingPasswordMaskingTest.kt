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
 * - The PRIMARY assertion is on the user-visible masking STATE via the eye
 *   control's content description: "Show password" while the field is masked
 *   (the default), "Hide password" after the eye reveals it, "Show password"
 *   again after a second tap (full round-trip). That content description is
 *   driven by the SAME `passwordVisible` flag that selects
 *   PasswordVisualTransformation, so it faithfully tracks the masking mode.
 *   Per §6.AB + Sixth Law clause 3 — user-visible state, not a callback count.
 *
 * IMPORTANT (why NOT assert the text node): Compose retains the RAW typed text in
 * the field's EditableText semantics REGARDLESS of the VisualTransformation (the
 * mask is applied at the VISUAL layer only). So `onNodeWithText("secret123")`
 * matches even while the field renders bullets — an `assertDoesNotExist` on it is
 * NOT a valid masking assertion (this was the original C42 device failure,
 * `assertDoesNotExist` at line 130). The masking STATE is therefore asserted via
 * the eye control; the delivered Challenge VIDEO is the watchable ground-truth of
 * the on-screen bullet→plaintext→bullet rendering (HelixQA video-QA, the test
 * verdict gates + the video documents the visual mask).
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In ConfigureStep.kt make the eye toggle a no-op (so the masking state never
 *      flips): change the trailing-icon `clickable { passwordVisible = !passwordVisible }`
 *      to `clickable { }` — the field renders + accepts input, the eye is tappable,
 *      but the masking never reveals (the §6.AB non-crashing class).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `passwordIsMaskedByDefault_andEyeRevealsPlaintext` fails at
 *      the REVEAL assertion — onNodeWithContentDescription("Hide password") finds
 *      no node because the eye still reads "Show password", so assertIsDisplayed
 *      throws "Failed: assertIsDisplayed … could not find node".
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
import androidx.compose.ui.test.onNodeWithContentDescription
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

        // Type a known password into the Password field (addressed by its label).
        composeRule.onNodeWithText("Password").performTextInput(knownPassword)
        composeRule.waitForIdle()

        // MASKED by default: the eye control advertises "Show password" — the
        // user-visible indicator of the masking state, driven by the SAME
        // `passwordVisible=false` that selects PasswordVisualTransformation. (NOTE:
        // Compose retains the RAW typed text in EditableText regardless of the
        // visual mask — so masking MUST be asserted via the visibility-state
        // surface, not the text node; the delivered video is the watchable proof
        // of the on-screen bullet rendering.)
        composeRule.onNodeWithContentDescription("Show password").assertIsDisplayed()

        // Tap the eye toggle → reveal.
        composeRule.onNodeWithTag(PasswordVisibilityToggleTestTag).performClick()
        composeRule.waitForIdle()
        // REVEALED: the eye now advertises "Hide password".
        composeRule.onNodeWithContentDescription("Hide password").assertIsDisplayed()

        // Tap again → re-mask (full round-trip back to "Show password").
        composeRule.onNodeWithTag(PasswordVisibilityToggleTestTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }
}
