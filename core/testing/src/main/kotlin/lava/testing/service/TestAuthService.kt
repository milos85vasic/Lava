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

    override suspend fun refreshToken(): Boolean = false
}
