package lava.network.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Discriminated union of authentication outcomes.
 *
 * Wire-shape note (added 2026-05-17, Bug 1 full-fix cycle): the
 * `ServiceUnavailable` variant is a NEW addition for the Android-side
 * SDK path (RuTrackerNetworkApi → SwitchingNetworkApi). It distinguishes
 * "credentials were rejected by the upstream" (WrongCredits) from
 * "the upstream produced an error that prevented us from knowing
 * whether credentials are correct" (ServiceUnavailable — Cloudflare 5xx,
 * parser Unknown, network failure, captcha-parse failure, etc.).
 *
 * The §6.J / §6.AB anti-bluff principle requires this distinction: the
 * old catch-all that mapped EVERY throwable to WrongCredits told the
 * user "your password is wrong" when in fact the system had no idea.
 * Forensic anchor: operator-reported "Cant login to RuTracker with
 * valid credentials" on Lava-Android-1.2.23-1043 (§6.L 57th invocation).
 *
 * Wire-compatibility note: the Go API (`lava-api-go`) currently does
 * NOT emit ServiceUnavailable — its login handler only ever produces
 * Success / WrongCredits / CaptchaRequired via the structured rutracker
 * scraper. The parity test in `tests/parity/` will continue to hold.
 * The OpenAPI spec gains the variant under `oneOf` so any future
 * server-side path that wants to emit it can; the discriminator
 * mapping is `ServiceUnavailable`.
 */
@Serializable
sealed interface AuthResponseDto {
    @Serializable
    @SerialName("Success")
    data class Success(val user: UserDto) : AuthResponseDto

    @Serializable
    @SerialName("WrongCredits")
    data class WrongCredits(val captcha: CaptchaDto?) : AuthResponseDto

    @Serializable
    @SerialName("CaptchaRequired")
    data class CaptchaRequired(val captcha: CaptchaDto?) : AuthResponseDto

    /**
     * Upstream produced an error before we could determine the
     * outcome. `reason` is a short human-readable + grep-friendly tag
     * ("Unknown: parser found no expected markers", "HttpException 503",
     * "CloudflareBlocked: …"). The UI MUST render this verbatim so the
     * user (and operator triaging via Crashlytics non-fatals) can tell
     * "wrong password" from "service blocked us". Crucially the user
     * MUST NOT see "Wrong credentials" when this fires — that is the
     * exact §6.J bluff this variant exists to evict.
     *
     * `captcha` is reserved for the future case where the upstream's
     * error response still carries a captcha image (e.g. anti-DDoS
     * mid-form challenges). Today it is always null.
     */
    @Serializable
    @SerialName("ServiceUnavailable")
    data class ServiceUnavailable(
        val reason: String,
        val captcha: CaptchaDto? = null,
    ) : AuthResponseDto
}
