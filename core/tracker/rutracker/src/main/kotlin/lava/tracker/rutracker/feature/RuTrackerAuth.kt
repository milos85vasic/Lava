package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.LoginUseCase
import lava.tracker.rutracker.domain.LogoutUseCase
import lava.tracker.rutracker.mapper.AuthMapper
import javax.inject.Inject

/**
 * RuTracker implementation of [AuthenticatableTracker]. Wraps the legacy
 * LoginUseCase / LogoutUseCase / CheckAuthorisedUseCase and exposes the
 * tracker-api auth surface.
 *
 * checkAuthAlive() is the lightweight liveness probe used by
 * [lava.tracker.rutracker.RuTrackerClient.healthCheck]. Because the
 * legacy CheckAuthorisedUseCase requires a token, and TokenProvider may
 * throw when none is stored, the call is wrapped in try-catch so that
 * "not authenticated" is reported as false (legitimate signal) rather
 * than propagating as a healthcheck failure.
 */
class RuTrackerAuth @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val checkUseCase: CheckAuthorisedUseCase,
    private val mapper: AuthMapper,
    private val tokenProvider: TokenProvider,
) : AuthenticatableTracker {

    override suspend fun login(req: LoginRequest): LoginResult {
        val captcha = req.captcha
        val dto = loginUseCase(
            username = req.username,
            password = req.password,
            captchaSid = captcha?.sid,
            captchaCode = captcha?.code,
            captchaValue = captcha?.value,
        )
        return mapper.toLoginResult(dto)
    }

    override suspend fun logout() {
        logoutUseCase()
    }

    override suspend fun checkAuth(): AuthState = if (checkAuthAlive()) {
        AuthState.Authenticated
    } else {
        AuthState.Unauthenticated
    }

    /** Internal hook used by [lava.tracker.rutracker.RuTrackerClient.healthCheck]. */
    suspend fun checkAuthAlive(): Boolean = try {
        checkUseCase(tokenProvider.getToken())
    } catch (_: Throwable) {
        false
    }
}
