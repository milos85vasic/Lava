package lava.testing.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import lava.auth.api.AuthService
import lava.auth.api.TokenProvider
import lava.models.auth.AuthResult
import lava.models.auth.AuthState

class TestAuthService : AuthService, TokenProvider {
    var response: AuthResult = AuthResult.Error(Throwable())
    val authState = MutableStateFlow<AuthState>(AuthState.Unauthorized)

    override suspend fun getToken(): String = ""

    override suspend fun isAuthorized(): Boolean = authState.value is AuthState.Authorized

    override fun observeAuthState(): Flow<AuthState> = authState

    override suspend fun logout() {
        authState.value = AuthState.Unauthorized
    }

    companion object {
        val TestAuthState = AuthState.Authorized(
            name = "Test User",
            avatarUrl = null,
        )
    }

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): AuthResult = response

    /**
     * Behaviorally-equivalent fake of [AuthService.signalAuthorized] —
     * mirrors the production AuthServiceImpl which emits Authorized into
     * the SharedFlow without touching persistence. Anti-Bluff Pact's
     * Third Law: a fake that drops this side effect would diverge from
     * production and produce a bluff fake. The recorded `signaledNames`
     * lets Challenge tests assert that the multi-tracker login flow
     * actually bridged through this seam.
     */
    val signaledNames = mutableListOf<String>()

    override suspend fun signalAuthorized(name: String, avatarUrl: String?) {
        signaledNames.add(name)
        authState.value = AuthState.Authorized(name, avatarUrl)
    }

    override suspend fun refreshToken(): Boolean = false
}
