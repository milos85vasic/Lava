package lava.login

import androidx.compose.ui.text.input.TextFieldValue
import lava.models.auth.Captcha

/**
 * State for the legacy single-tracker login screen.
 *
 * Bug 1 (2026-05-17, §6.L 57th invocation) added `serviceUnavailable`
 * so the UI can render the user-visible "Service unavailable" message
 * with a structured reason string when an upstream infrastructure
 * error (Cloudflare block, parser Unknown, network failure, captcha-
 * parse failure) prevents the auth attempt from completing. The
 * §6.J anti-bluff requirement: this message MUST replace the prior
 * silent "wrong credentials" rendering.
 */
data class LoginState(
    val isLoading: Boolean = false,
    val usernameInput: InputState = InputState.Initial,
    val passwordInput: InputState = InputState.Initial,
    val captchaInput: InputState = InputState.Initial,
    val captcha: Captcha? = null,
    val serviceUnavailable: String? = null,
)

sealed interface InputState {
    val value: TextFieldValue
        get() = TextFieldValue()

    data object Initial : InputState

    data object Empty : InputState

    data class Valid(override val value: TextFieldValue) : InputState

    data class Invalid(override val value: TextFieldValue) : InputState

    fun isValid() = this is Valid

    fun isError() = this is Invalid || this is Empty
}

val LoginState.hasCaptcha: Boolean
    get() = captcha != null

val LoginState.isValid: Boolean
    get() = usernameInput.isValid() &&
        passwordInput.isValid() &&
        (!hasCaptcha || captchaInput.isValid())
