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

    /**
     * Phase 1.1 (2026-05-04) — persistence-equivalent in-memory map
     * mirroring the production [lava.auth.impl.AuthServiceImpl] +
     * [lava.securestorage.PreferencesStorageImpl] pair. The production
     * pair writes the signaled-auth state to a separate SharedPreferences
     * file (`signaled_auth.xml`) so a force-quit + relaunch restores the
     * authorized state for users who completed a no-auth provider flow
     * (Internet Archive, Project Gutenberg). Anti-Bluff Pact's Third Law:
     * dropping this field from the fake would diverge from production —
     * a test that simulates process restart against this fake would
     * silently lose the signal while the real production stack would
     * restore it, producing a bluff fake.
     *
     * [logout] clears both [authState] and [persistedSignaledState],
     * matching the production logout semantics.
     * [simulateProcessRestart] mirrors the production cold-start path:
     * resets the in-memory observable (process death) and re-emits from
     * the persisted state if any (the equivalent of
     * [lava.auth.impl.AuthServiceImpl.getAuthState] consulting
     * [lava.securestorage.PreferencesStorage.getSignaledAuthState] on
     * first observation).
     */
    var persistedSignaledState: AuthState.Authorized? = null

    override suspend fun logout() {
        persistedSignaledState = null
        authState.value = AuthState.Unauthorized
    }

    /**
     * Test helper: simulate process death + relaunch. Resets the
     * SharedFlow-equivalent observable to the cold-start path, then
     * consults the persisted signal exactly the way production does.
     * Tests that need to assert "force-stop survival" of the authorized
     * state call this between Act and Assert.
     */
    fun simulateProcessRestart() {
        signaledNames.clear()
        authState.value = persistedSignaledState ?: AuthState.Unauthorized
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
        val newState = AuthState.Authorized(name, avatarUrl)
        // Match the production AuthServiceImpl.signalAuthorized semantics:
        // write to BOTH the in-memory observable AND the persisted store.
        persistedSignaledState = newState
        authState.value = newState
    }

    override suspend fun refreshToken(): Boolean = false
}
