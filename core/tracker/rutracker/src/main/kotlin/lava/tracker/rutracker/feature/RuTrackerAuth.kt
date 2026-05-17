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
        // Bug 1 v2 (2026-05-17, §6.L 59th invocation, post-sweep retest).
        //
        // Bug 1's structural fix (commit ee643e7f) added the catch in
        // `RuTrackerNetworkApi.login()` that maps Throwable → ServiceUnavailable.
        // But the multi-tracker SDK code path uses `RuTrackerAuth.login()`
        // (this method) → `LoginUseCase.invoke()` directly, bypassing
        // RuTrackerNetworkApi entirely. When LoginUseCase throws NoData
        // (HTML lacks both login-form key AND token), the exception
        // escapes this method and crashes ProviderLoginViewModel's
        // coroutine, killing the app process.
        //
        // Wrap the same defensive catch here so the SDK path also
        // surfaces structured ServiceUnavailable instead of crashing.
        // Mirror the marker + telemetry shape from RuTrackerNetworkApi.
        val dto = try {
            loginUseCase(
                username = req.username,
                password = req.password,
                captchaSid = captcha?.sid,
                captchaCode = captcha?.code,
                captchaValue = captcha?.value,
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            System.err.println(
                "RuTrackerAuth.login: NOT-actually-wrong-credentials — " +
                    "upstream produced ${t.javaClass.simpleName}: ${t.message ?: "<no message>"} " +
                    "(returning ServiceUnavailable per §6.J anti-bluff; reason will reach UI)",
            )
            t.printStackTrace()
            lava.network.dto.auth.AuthResponseDto.ServiceUnavailable(
                reason = "${t.javaClass.simpleName}: ${t.message ?: "<no message>"}",
            )
        }
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
