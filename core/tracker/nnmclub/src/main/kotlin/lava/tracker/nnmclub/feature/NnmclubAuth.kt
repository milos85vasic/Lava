package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubLoginParser
import javax.inject.Inject

/**
 * NNM-Club implementation of [AuthenticatableTracker].
 *
 * URL contract: `<baseUrl>/forum/login.php` (POST).
 * Form fields: `username`, `password`, `login=Вход`.
 */
class NnmclubAuth @Inject constructor(
    private val http: NnmclubHttpClient,
    private val parser: NnmclubLoginParser,
) : AuthenticatableTracker {

    internal constructor(
        http: NnmclubHttpClient,
        parser: NnmclubLoginParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun login(req: LoginRequest): LoginResult {
        val response = http.postForm(
            url = "$baseUrl/forum/login.php",
            fields = mapOf(
                "username" to req.username,
                "password" to req.password,
                "login" to "Вход",
            ),
        )
        val body = response.use { it.body?.string() ?: "" }
        val parsed = parser.parse(body)
        val token = http.cookieValue(PHPBB_COOKIE)
        return parsed.copy(sessionToken = token)
    }

    override suspend fun logout() {
        http.clearCookies()
    }

    override suspend fun checkAuth(): AuthState =
        if (http.hasCookie(PHPBB_COOKIE)) AuthState.Authenticated else AuthState.Unauthenticated

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
        const val PHPBB_COOKIE: String = "phpbb2mysql_4_data"
    }
}
