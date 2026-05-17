package lava.login

import lava.models.auth.AuthResult
import lava.models.auth.Captcha
import lava.tracker.api.model.LoginResult

/**
 * Maps tracker-sdk [LoginResult] to the legacy domain [AuthResult]
 * used by the UI layer.
 *
 * Added in Multi-Provider Extension (US2).
 *
 * Bug 1 follow-up (2026-05-17, §6.L 57th invocation): the
 * ServiceUnavailable branch maps to [AuthResult.ServiceUnavailable].
 * This is the §6.J anti-bluff propagation: tests / Challenges / the
 * UI layer all see a distinct value so the rendering code can show
 * "Service unavailable. Please try again later. (reason)" instead of
 * the silently-bluffing "Wrong credentials" the old catch-all produced.
 */
internal fun LoginResult.toAuthResult(): AuthResult = when (val s = state) {
    is lava.tracker.api.model.AuthState.Authenticated -> AuthResult.Success
    is lava.tracker.api.model.AuthState.Unauthenticated -> AuthResult.WrongCredits(
        captcha = captchaChallenge?.toCaptcha(),
    )
    is lava.tracker.api.model.AuthState.CaptchaRequired -> AuthResult.CaptchaRequired(
        captcha = s.challenge.toCaptcha(),
    )
    is lava.tracker.api.model.AuthState.ServiceUnavailable -> AuthResult.ServiceUnavailable(
        reason = s.reason,
    )
}

private fun lava.tracker.api.model.CaptchaChallenge.toCaptcha(): Captcha = Captcha(
    id = sid,
    code = code,
    url = imageUrl,
)
