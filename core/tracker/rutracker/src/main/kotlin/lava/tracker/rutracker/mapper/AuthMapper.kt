package lava.tracker.rutracker.mapper

import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.CaptchaDto
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.CaptchaChallenge
import lava.tracker.api.model.LoginResult
import javax.inject.Inject

/**
 * Maps the legacy [AuthResponseDto] (sealed: Success / WrongCredits /
 * CaptchaRequired / ServiceUnavailable) to the new tracker-api
 * [LoginResult].
 *
 * Branches:
 *  - Success(user)        → state=Authenticated, sessionToken=user.token
 *  - WrongCredits(captcha?) → state=Unauthenticated; if a captcha is
 *      attached, surface it as captchaChallenge so the next login attempt
 *      can present it. (Plain wrong-password without captcha → null
 *      captchaChallenge.)
 *  - CaptchaRequired(captcha?) → state=CaptchaRequired(challenge) when
 *      captcha is present; if rutracker emits a CaptchaRequired with a
 *      null captcha the response is malformed — we degrade to
 *      Unauthenticated rather than constructing a CaptchaRequired with
 *      a synthetic challenge (which would mislead the UI into showing a
 *      blank challenge image).
 *  - ServiceUnavailable(reason) → state=ServiceUnavailable(reason). Bug 1
 *      (2026-05-17, §6.L 57th invocation). Distinct from Unauthenticated:
 *      the upstream produced an infrastructure error before the auth
 *      could complete. The UI MUST render `reason` and MUST NOT show
 *      "wrong credentials" — §6.J anti-bluff requirement.
 *
 * Information-loss notes (Section E):
 *  - The legacy CaptchaDto carries the actual rutracker form-field name
 *    in `code` (e.g. "cap_code_xxxxx"), not a human-visible token. The
 *    new CaptchaChallenge.code preserves it verbatim. The reverse mapper
 *    has all the information it needs.
 *  - UserDto.id and UserDto.avatarUrl are dropped on the forward path —
 *    LoginResult only carries sessionToken. The reverse mapper
 *    cannot reconstruct them; callers that need profile data should use
 *    the dedicated profile endpoint.
 */
class AuthMapper @Inject constructor() {
    fun toLoginResult(dto: AuthResponseDto): LoginResult = when (dto) {
        is AuthResponseDto.Success -> LoginResult(
            state = AuthState.Authenticated,
            sessionToken = dto.user.token,
            captchaChallenge = null,
        )
        is AuthResponseDto.WrongCredits -> LoginResult(
            state = AuthState.Unauthenticated,
            sessionToken = null,
            captchaChallenge = dto.captcha?.toChallenge(),
        )
        is AuthResponseDto.CaptchaRequired -> {
            val challenge = dto.captcha?.toChallenge()
            if (challenge != null) {
                LoginResult(
                    state = AuthState.CaptchaRequired(challenge),
                    sessionToken = null,
                    captchaChallenge = challenge,
                )
            } else {
                LoginResult(
                    state = AuthState.Unauthenticated,
                    sessionToken = null,
                    captchaChallenge = null,
                )
            }
        }
        is AuthResponseDto.ServiceUnavailable -> LoginResult(
            state = AuthState.ServiceUnavailable(dto.reason),
            sessionToken = null,
            captchaChallenge = dto.captcha?.toChallenge(),
        )
    }
}

internal fun CaptchaDto.toChallenge(): CaptchaChallenge =
    CaptchaChallenge(sid = id, code = code, imageUrl = url)
