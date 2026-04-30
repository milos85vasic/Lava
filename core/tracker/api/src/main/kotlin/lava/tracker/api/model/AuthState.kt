package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
sealed class AuthState {
    @Serializable
    object Authenticated : AuthState()

    @Serializable
    object Unauthenticated : AuthState()

    @Serializable
    data class CaptchaRequired(val challenge: CaptchaChallenge) : AuthState()
}
