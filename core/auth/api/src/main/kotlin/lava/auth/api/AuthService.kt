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

    /**
     * Bridge for the multi-tracker login flow (ProviderLoginViewModel) to
     * notify the legacy auth-state observation path (used by Search/Forum/
     * Topic screens via ObserveAuthStateUseCase) that the user has
     * successfully completed a provider's login or anonymous-continue
     * action.
     *
     * Forensic anchor (clauses 6.G/6.J/6.L): Without this signal, picking
     * an AuthType.NONE provider (Internet Archive, Project Gutenberg) or
     * completing a multi-tracker FORM_LOGIN through the new flow leaves
     * the legacy AuthService SharedFlow at AuthState.Unauthorized — the
     * Search tab shows "Authorization required to search" and the user is
     * stranded one tap short of a working flow. The 2026-05-04 emulator
     * rehearsal exposed this; see
     * .lava-ci-evidence/sixth-law-incidents/2026-05-04-onboarding-navigation.json
     * (`follow_up_finding_legacy_auth_path_still_used_by_search_screen`).
     *
     * Implementation MUST emit AuthState.Authorized(name, avatarUrl=null)
     * into the same SharedFlow that observeAuthState() returns. No
     * persistence is required at this seam; persistence belongs to
     * ProviderCredentialManager. A subsequent `logout()` call clears it.
     */
    suspend fun signalAuthorized(name: String, avatarUrl: String? = null)
}
