package lava.auth.api

import kotlinx.coroutines.flow.Flow
import lava.models.auth.AuthResult
import lava.models.auth.AuthState

interface AuthService {
    fun observeAuthState(): Flow<AuthState>
    suspend fun isAuthorized(): Boolean
    suspend fun login(
        username: String,
        password: String,
        captchaSid: String? = null,
        captchaCode: String? = null,
        captchaValue: String? = null,
    ): AuthResult

    suspend fun logout()
}
