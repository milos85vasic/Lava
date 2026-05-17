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

    /**
     * Auth attempt could NOT be completed because the upstream produced an
     * infrastructure error (Cloudflare block, parser Unknown, network
     * failure, captcha-parse failure). This is structurally distinct from
     * [Unauthenticated]: the credentials may be correct or incorrect; we
     * do not know. The UI MUST surface `reason` to the user verbatim and
     * MUST NOT display a "wrong credentials" message — that is the §6.J
     * bluff that this state exists to evict (Bug 1, 2026-05-17, §6.L 57th
     * invocation, forensic anchor "Cant login to RuTracker with valid
     * credentials").
     */
    @Serializable
    data class ServiceUnavailable(val reason: String) : AuthState()
}
