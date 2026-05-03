package lava.tracker.kinozal.feature

import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.kinozal.http.KinozalHttpClient
import javax.inject.Inject

/**
 * Kinozal implementation of [AuthenticatableTracker].
 *
 * URL contract: POST `<baseUrl>/takelogin.php` with `username`, `password`,
 * `returnto=%2F`.
 *
 * Session token: after a successful POST kinozal sets a `uid` cookie.
 */
class KinozalAuth @Inject constructor(
    private val http: KinozalHttpClient,
) : AuthenticatableTracker {

    internal constructor(http: KinozalHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun login(req: LoginRequest): LoginResult {
        val response = http.postForm(
            url = "$baseUrl/takelogin.php",
            fields = mapOf(
                "username" to req.username,
                "password" to req.password,
                "returnto" to "%2F",
            ),
        )
        response.use {
            // Consume body so the connection can be pooled.
            http.bodyString(it)
        }
        val token = http.cookieValue(UID_COOKIE)
        return if (token != null) {
            LoginResult(state = AuthState.Authenticated, sessionToken = token)
        } else {
            LoginResult(state = AuthState.Unauthenticated)
        }
    }

    override suspend fun logout() {
        http.clearCookies()
    }

    override suspend fun checkAuth(): AuthState =
        if (http.hasCookie(UID_COOKIE)) AuthState.Authenticated else AuthState.Unauthenticated

    companion object {
        const val DEFAULT_BASE_URL: String = "https://kinozal.tv"
        const val UID_COOKIE: String = "uid"
    }
}
