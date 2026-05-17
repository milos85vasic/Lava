package lava.models.auth

sealed interface AuthResult {
    data object Success : AuthResult
    data class WrongCredits(val captcha: Captcha?) : AuthResult
    data class CaptchaRequired(val captcha: Captcha) : AuthResult
    data class Error(val error: Throwable) : AuthResult

    /**
     * Bug 1 (2026-05-17, §6.L 57th invocation) — Distinct from
     * [WrongCredits]: the upstream produced an error (Cloudflare 5xx,
     * parser Unknown, network failure, captcha-parse failure) before
     * we could determine whether the credentials are valid. The UI
     * MUST display [reason] verbatim and MUST NOT show "wrong
     * credentials". This is the load-bearing §6.J anti-bluff
     * distinction the variant exists to enforce.
     */
    data class ServiceUnavailable(val reason: String) : AuthResult
}

data class Captcha(
    val id: String,
    val code: String,
    val url: String,
)
