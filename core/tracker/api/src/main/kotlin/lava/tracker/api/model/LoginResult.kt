package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginResult(
    val state: AuthState,
    val sessionToken: String? = null,
    val captchaChallenge: CaptchaChallenge? = null,
)
