package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val captcha: CaptchaSolution? = null,
)
