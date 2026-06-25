/*
 * Challenge Test C54 — Credentials edit dialog: the Password field renders MASKED
 * (a real user-visible security property), the Username field does NOT.
 *
 * GAP CLOSED (UI coverage audit `docs/qa/2026-06-25-ui-coverage-audit.md`, R5 /
 * W5): the audit flagged "password masking in the manager / edit dialog" as
 * uncovered at the rendered-UI layer. A credential-entry dialog that renders the
 * password in plaintext is a real security defect a user (or a shoulder-surfer)
 * can SEE. This Challenge asserts the production `CredentialEditDialog` applies
 * password masking to the Password field.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   The Composable rendered is the SAME production
 *   `lava.feature.credentials.CredentialEditDialog` the production
 *   `CredentialsScreen` instantiates — no mock dialog, no fake field. The masking
 *   under test lives INSIDE that production composable:
 *     OutlinedTextField(value = state.password, …,
 *                       visualTransformation = PasswordVisualTransformation())
 *   so this test exercises the real masking wiring directly.
 *
 *   HOW masking is asserted (the §6.J-honest way — learned from C42's device
 *   failure): Compose retains the RAW typed text in a field's EditableText
 *   semantics REGARDLESS of the visual mask (the mask is applied only at the
 *   VISUAL layer). So `assertDoesNotExist` on the plaintext is NOT a valid
 *   masking assertion. The faithful, user-visible signal is the
 *   `SemanticsProperties.Password` flag — Compose sets it on a text field IFF a
 *   `PasswordVisualTransformation` is applied, and accessibility services read it
 *   to announce the field as a password and suppress plaintext readout. The
 *   PRIMARY assertion is therefore: the Password field node carries the Password
 *   semantics property; the Username field does NOT (discrimination — proving the
 *   assertion is specific to the masked field, not trivially true for every
 *   field).
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect → assertion fails):
 *
 *   MUTATION — drop the masking from the Password field (the dialog still
 *   renders, the field still accepts input, nothing crashes — the §6.AB
 *   non-crashing class, and the exact security defect this guards against):
 *     1. In `CredentialEditDialog`, the PASSWORD branch's password
 *        `OutlinedTextField`, delete the line
 *          `visualTransformation = PasswordVisualTransformation(),`
 *        so the password renders in plaintext.
 *     2. Re-run on the gating emulator/device:
 *          ./gradlew :app:connectedDebugAndroidTest \
 *            --tests "lava.app.challenges.Challenge54CredentialEditDialogPasswordMaskedTest"
 *     3. Expected failure: the Password field no longer carries the Password
 *        semantics property, so `assert(isPassword())` on it fails with
 *          "Failed to assert the following: (SemanticsProperties.Password is set)"
 *        — the masking has been removed and the plaintext password is now visible
 *        on screen.
 *     4. Revert the mutation; re-run; the field is masked again and the assertion
 *        passes.
 *
 * Operator command (device run via the §6.AE Containers matrix, §6.AH container/VM):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge54CredentialEditDialogPasswordMaskedTest"
 *
 * Honest scope statement (per §6.J / §6.X-debt / §6.AH): SOURCE-WRITTEN here;
 * COMPILE + DEVICE-EXEC are PENDING (the coordinated 1076 gate compiles
 * `:app:assembleDebugAndroidTest`; emulator execution is deferred to the §6.AE
 * Containers/VM gate-host per §6.AH — no host-direct fallback on this
 * darwin/arm64 host). The §6.AE.5 attestation row is produced on the matrix.
 *
 * // covers-feature: credentials
 */
package lava.app.challenges

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import lava.designsystem.theme.LavaTheme
import lava.feature.credentials.CredentialDialogState
import lava.feature.credentials.CredentialEditDialog
import lava.feature.credentials.CredentialType
import lava.feature.credentials.CredentialsAction
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
class Challenge54CredentialEditDialogPasswordMaskedTest {

    @get:Rule
    val composeRule = createComposeRule()

    // CHALLENGE — render the REAL CredentialEditDialog (PASSWORD type), type a
    // password, and assert the Password field carries the Password semantics
    // property (masked) while the Username field does not. Primary assertion on
    // the user-visible masking state.
    @Test
    fun passwordFieldIsMasked_usernameFieldIsNot() {
        composeRule.setContent {
            // Real hoisted dialog state so onValueChange actually updates the
            // production binding `value = state.password` / `state.username` —
            // the same CredentialDialogState the production CredentialsViewModel
            // feeds the dialog.
            var state by mutableStateOf(
                CredentialDialogState(
                    providerId = "rutracker",
                    providerDisplayName = "RuTracker",
                    credentialType = CredentialType.PASSWORD,
                ),
            )
            LavaTheme {
                CredentialEditDialog(
                    state = state,
                    onAction = { action ->
                        state = when (action) {
                            is CredentialsAction.SetUsername ->
                                state.copy(username = action.username)
                            is CredentialsAction.SetPassword ->
                                state.copy(password = action.password)
                            else -> state
                        }
                    },
                )
            }
        }

        // Address the FIELDS specifically (not the "Password" type-selector
        // BUTTON, which also carries the text "Password"): a text FIELD has a
        // set-text action; the selector button does not.
        val usernameField = hasSetTextAction() and hasText("Username")
        val passwordField = hasSetTextAction() and hasText("Password")

        // Type into the real Username + Password fields.
        composeRule.onNode(usernameField).performTextInput("vasya")
        composeRule.onNode(passwordField).performTextInput("hunter2")
        composeRule.waitForIdle()

        // PRIMARY ASSERTION — the Password field is MASKED: Compose sets the
        // Password semantics property IFF a PasswordVisualTransformation is
        // applied. This is the user-visible (accessibility-announced) security
        // property a real user / shoulder-surfer relies on.
        composeRule.onNode(passwordField).assert(isPasswordField())

        // DISCRIMINATION — the Username field must NOT be masked, proving the
        // assertion is specific to the password field and not trivially true.
        composeRule.onNode(usernameField)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    private fun isPasswordField(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
}
