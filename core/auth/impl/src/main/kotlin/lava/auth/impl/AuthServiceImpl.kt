package lava.auth.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onStart
import lava.auth.api.AuthService
import lava.auth.api.TokenProvider
import lava.common.SingleItemMutableSharedFlow
import lava.models.auth.AuthResult
import lava.models.auth.AuthState
import lava.models.auth.Captcha
import lava.network.api.NetworkApi
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.CaptchaDto
import lava.securestorage.PreferencesStorage
import lava.securestorage.model.Account
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AuthServiceImpl @Inject constructor(
    private val api: NetworkApi,
    private val preferencesStorage: PreferencesStorage,
) : AuthService, TokenProvider {
    private val mutableAuthState = SingleItemMutableSharedFlow<AuthState>()

    // 2026-05-04 layer-3 fix: in-memory "session signaled" auth state.
    // Late-subscribing observers (e.g. SearchViewModel that initializes
    // AFTER onboarding completes) do NOT see SharedFlow replays because
    // SingleItemMutableSharedFlow uses replay=0. Without this field, the
    // bridge from ProviderLoginViewModel.signalAuthorized to the
    // legacy-auth observers would silently no-op after the screen
    // transition. onStart emits getAuthState() which now consults this
    // field, so a new observer sees the right state.
    @Volatile
    private var sessionSignaledState: AuthState? = null

    override fun observeAuthState(): Flow<AuthState> = mutableAuthState
        .asSharedFlow()
        .onStart { emit(getAuthState()) }

    override suspend fun isAuthorized(): Boolean = getAuthState() is AuthState.Authorized

    override suspend fun getToken(): String = preferencesStorage.getAccount()?.token.orEmpty()

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): AuthResult {
        fun CaptchaDto?.toCaptcha(): Captcha? = this?.let { Captcha(id, code, url) }
        return when (
            val dto = api.login(
                username = username,
                password = password,
                captchaSid = captchaSid,
                captchaCode = captchaCode,
                captchaValue = captchaValue,
            )
        ) {
            is AuthResponseDto.CaptchaRequired -> {
                AuthResult.CaptchaRequired(requireNotNull(dto.captcha.toCaptcha()))
            }

            is AuthResponseDto.Success -> {
                val (id, token, avatarUrl) = dto.user
                saveAccount(Account(id, username, password, token, avatarUrl))
                AuthResult.Success
            }

            is AuthResponseDto.WrongCredits -> {
                AuthResult.WrongCredits(dto.captcha.toCaptcha())
            }
        }
    }

    override suspend fun refreshToken(): Boolean {
        val account = preferencesStorage.getAccount()
        if (account != null) {
            val dto = api.login(account.name, account.password, null, null, null)
            if (dto is AuthResponseDto.Success) {
                saveAccount(account.copy(token = dto.user.token))
                return true
            }
        }
        logout()
        return false
    }

    override suspend fun logout() {
        preferencesStorage.clearAccount()
        sessionSignaledState = null
        mutableAuthState.emit(AuthState.Unauthorized)
    }

    /**
     * 2026-05-04 multi-tracker bridge — see AuthService KDoc. Emits an
     * Authorized state into the same SharedFlow that observeAuthState()
     * returns, without touching preferencesStorage (the legacy single-
     * tracker account store remains the source of truth for legacy login
     * flows; this bridge is purely an observation-layer signal so the
     * Search tab unblocks for users who completed a provider flow through
     * the new ProviderLoginViewModel path).
     *
     * Falsifiability rehearsal protocol (Sixth Law clause 2):
     *   1. Comment out the mutableAuthState.emit(...) line below.
     *   2. Manual rehearsal on the gating emulator: pick Internet Archive,
     *      tap Continue, observe Search tab.
     *   3. Expected failure: Search tab still renders "Authorization
     *      required to search" because the SharedFlow never updates from
     *      its onStart-emitted Unauthorized.
     *   4. Revert the comment; re-rehearse; Search tab renders the
     *      authorized empty state with a search input.
     */
    override suspend fun signalAuthorized(name: String, avatarUrl: String?) {
        val newState = AuthState.Authorized(name, avatarUrl)
        sessionSignaledState = newState
        mutableAuthState.emit(newState)
    }

    private suspend fun saveAccount(account: Account) {
        preferencesStorage.saveAccount(account)
        mutableAuthState.emit(AuthState.Authorized(account.name, account.avatarUrl))
    }

    private suspend fun getAuthState(): AuthState {
        // Prefer the in-memory session-signaled state (set by
        // signalAuthorized) over the persisted-account state, so a
        // late-subscribing observer sees the correct authorized state
        // even though SharedFlow replay=0 dropped the original emission.
        sessionSignaledState?.let { return it }
        val account = preferencesStorage.getAccount()
        return if (account != null) {
            AuthState.Authorized(account.name, account.avatarUrl)
        } else {
            AuthState.Unauthorized
        }
    }
}
