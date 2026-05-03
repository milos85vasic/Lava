package lava.login

import lava.models.auth.AuthResult
import lava.models.auth.Captcha
import lava.tracker.api.model.LoginResult

/**
 * Maps tracker-sdk [LoginResult] to the legacy domain [AuthResult]
 * used by the UI layer.
 *
 * Added in Multi-Provider Extension (US2).
 */
internal fun LoginResult.toAuthResult(): AuthResult = when (val s = state) {
    is lava.tracker.api.model.AuthState.Authenticated -> AuthResult.Success
    is lava.tracker.api.model.AuthState.Unauthenticated -> AuthResult.WrongCredits(
        captcha = captchaChallenge?.toCaptcha(),
    )
    is lava.tracker.api.model.AuthState.CaptchaRequired -> AuthResult.CaptchaRequired(
        captcha = s.challenge.toCaptcha(),
    )
}

private fun lava.tracker.api.model.CaptchaChallenge.toCaptcha(): Captcha = Captcha(
    id = sid,
    code = code,
    url = imageUrl,
)
