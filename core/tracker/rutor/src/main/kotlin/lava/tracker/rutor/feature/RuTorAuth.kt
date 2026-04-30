package lava.tracker.rutor.feature

import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorLoginParser
import javax.inject.Inject

/**
 * RuTor implementation of [AuthenticatableTracker] (SP-3a Task 3.38, Section I).
 *
 * URL contract: `<baseUrl>/login.php` (POST). Verified by inspecting the
 * `<form action="...">` attribute in `login-form-2026-04-30.html` — the form
 * action attribute is literally "/login.php". Form fields mirror the input
 * names rutor's template uses: `nick` and `password`.
 *
 * Anonymous-by-default policy (SP-3a decision 7b-ii): the descriptor declares
 * AUTH_REQUIRED so this surface is reachable, but no read operation upstream
 * gates on a logged-in session. The SDK calls into [login] only when the user
 * deliberately initiates a login flow.
 *
 * Session token: after a successful POST rutor sets a `userid` cookie. The
 * cookie jar inside [RuTorHttpClient] persists it for subsequent requests.
 * [LoginResult.sessionToken] surfaces the cookie value so the host app can
 * cache "is logged in?" without re-issuing a checkAuth network round-trip.
 *
 * Logout: rutor has no first-class logout endpoint that anonymous code can
 * call without server-side session invalidation. The OkHttp cookie jar
 * cannot be cleared in-place without reflection, so [logout] is a documented
 * no-op — clearing the userid cookie requires app restart per the Section C
 * RuTorHttpClient.clearCookies KDoc. Pre-authorized adaptation acknowledges
 * this for Phase 3.
 *
 * Sixth Law clause 1: the form action and field names are exactly what
 * rutor.info's own login form posts; verified against the captured fixtures.
 */
class RuTorAuth @Inject constructor(
    private val http: RuTorHttpClient,
    private val parser: RuTorLoginParser,
) : AuthenticatableTracker {

    internal constructor(
        http: RuTorHttpClient,
        parser: RuTorLoginParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun login(req: LoginRequest): LoginResult {
        val response = http.postForm(
            url = "$baseUrl/login.php",
            fields = mapOf(
                "nick" to req.username,
                "password" to req.password,
            ),
        )
        val body = response.use { it.body?.string() ?: "" }
        val parsed = parser.parse(body)
        val token = http.cookieValue(USERID_COOKIE)
        return parsed.copy(sessionToken = token)
    }

    override suspend fun logout() {
        http.clearCookies()
    }

    override suspend fun checkAuth(): AuthState =
        if (http.hasCookie(USERID_COOKIE)) AuthState.Authenticated else AuthState.Unauthenticated

    companion object {
        const val DEFAULT_BASE_URL: String = "https://rutor.info"
        const val USERID_COOKIE: String = "userid"
    }
}
