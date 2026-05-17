package lava.auth.impl

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.auth.AuthState
import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.network.api.NetworkApi
import lava.network.dto.FileDto
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.user.FavoritesDto
import lava.securestorage.PreferencesStorage
import lava.securestorage.SignaledAuthState
import lava.securestorage.model.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.1 acceptance test (real-stack at the persistence boundary).
 *
 * Verifies that [AuthServiceImpl.signalAuthorized] persists across
 * process death and that a freshly-constructed [AuthServiceImpl]
 * pointing at the same [PreferencesStorage] surface restores the
 * authorized state on its first observation.
 *
 * Sixth Law alignment (clause 1, 2, 3):
 *   - Same surfaces the user touches: the production [AuthServiceImpl]
 *     is the SUT (no mocking it). The fake is at the persistence
 *     boundary below the SUT, behaviorally equivalent to
 *     [lava.securestorage.PreferencesStorageImpl] (separate keyspaces
 *     for account vs signaled-auth, returns null when no signal saved).
 *   - Falsifiable: see the per-test "Mutation rehearsal" comments. The
 *     production cold-start path lives in [AuthServiceImpl.getAuthState]
 *     at the `getSignaledAuthState()` consultation; mutating that read
 *     to return null unconditionally makes [coldStart_*] tests fail.
 *   - Primary assertion on user-visible state: [AuthState.Authorized]
 *     emitted into the SharedFlow that the Search/Forum/Topic screens
 *     consume via ObserveAuthStateUseCase.
 *
 * Bluff-Audit: AuthServiceImplPersistenceTest
 *   Mutation: replace `preferencesStorage.getSignaledAuthState()?.let { … }`
 *             in AuthServiceImpl.getAuthState() with `null?.let { … }`
 *             (effectively skip the cold-start signal restoration).
 *   Observed-Failure: `coldStart_restores_signaledAuthState_after_simulated_process_restart`
 *             expected `Authorized(name=Test User, …)` but got `Unauthorized` —
 *             the cold-start observer never saw the persisted signal.
 *   Reverted: yes
 */
class AuthServiceImplPersistenceTest {

    @Test
    fun `signalAuthorized persists the auth state to preferences storage`() = runTest {
        val storage = FakePreferencesStorage()
        val service = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)

        service.signalAuthorized(name = "archive.org user", avatarUrl = null)

        // Primary assertion (user-visible): the persisted state matches the signal
        assertEquals(
            SignaledAuthState(name = "archive.org user", avatarUrl = null),
            storage.getSignaledAuthState(),
        )
        // Secondary: the SharedFlow observable also emitted Authorized
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = null),
            service.observeAuthState().first(),
        )
    }

    @Test
    fun `coldStart_restores_signaledAuthState_after_simulated_process_restart`() = runTest {
        // Pre-populate persistence to simulate a prior session that called signalAuthorized
        val storage = FakePreferencesStorage().apply {
            saveSignaledAuthState(name = "archive.org user", avatarUrl = "http://example.test/a.png")
        }
        // Brand-new AuthServiceImpl instance — simulates app restart after force-stop
        val service = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)

        // Primary assertion: the first observation emits Authorized restored from disk
        val observed = service.observeAuthState().first()
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = "http://example.test/a.png"),
            observed,
        )
        // Secondary: isAuthorized() agrees
        assertTrue(service.isAuthorized())
    }

    @Test
    fun `coldStart_with_no_persisted_signal_emits_Unauthorized`() = runTest {
        val storage = FakePreferencesStorage() // empty
        val service = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)

        assertEquals(AuthState.Unauthorized, service.observeAuthState().first())
        assertEquals(false, service.isAuthorized())
    }

    @Test
    fun `coldStart_with_legacy_account_only_emits_Authorized_from_account`() = runTest {
        // Legacy single-tracker (RuTracker) account exists, no signaled-auth signal
        val storage = FakePreferencesStorage().apply {
            saveAccount(Account(id = "1", name = "rutracker user", password = "p", token = "t", avatarUrl = null))
        }
        val service = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)

        assertEquals(
            AuthState.Authorized(name = "rutracker user", avatarUrl = null),
            service.observeAuthState().first(),
        )
    }

    @Test
    fun `signaledAuthState_takes_precedence_over_legacy_account_when_both_exist`() = runTest {
        val storage = FakePreferencesStorage().apply {
            saveAccount(Account(id = "1", name = "legacy", password = "p", token = "t", avatarUrl = null))
            saveSignaledAuthState(name = "current session user", avatarUrl = null)
        }
        val service = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)

        // Production order: sessionSignaledState first, THEN persisted signal,
        // THEN legacy account. Persisted signal beats legacy account.
        assertEquals(
            AuthState.Authorized(name = "current session user", avatarUrl = null),
            service.observeAuthState().first(),
        )
    }

    @Test
    fun `logout_clears_persisted_signal_so_next_coldStart_emits_Unauthorized`() = runTest {
        val storage = FakePreferencesStorage()
        val service = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)
        service.signalAuthorized(name = "archive.org user", avatarUrl = null)
        assertEquals(
            SignaledAuthState(name = "archive.org user", avatarUrl = null),
            storage.getSignaledAuthState(),
        )

        service.logout()

        // Primary assertion: the persisted signal is gone after logout
        assertNull(storage.getSignaledAuthState())
        // Secondary: a fresh AuthServiceImpl on the same storage observes Unauthorized
        val coldStartService = AuthServiceImpl(api = StubNetworkApi(), preferencesStorage = storage)
        assertEquals(AuthState.Unauthorized, coldStartService.observeAuthState().first())
    }

    /**
     * Behaviorally-equivalent fake of [lava.securestorage.PreferencesStorageImpl]
     * for the [PreferencesStorage] surfaces this test exercises. Anti-Bluff
     * Pact's Third Law: enforces the SAME contract as the real impl —
     *   - Account keyspace (id+name+password+token+avatar) is separate from
     *     signaled-auth keyspace (name+avatar). Saving one does NOT touch
     *     the other; clearing one does NOT touch the other. Production
     *     keeps these in distinct SharedPreferences files
     *     ("account.xml" vs "signaled_auth.xml") for the same reason.
     *   - getSignaledAuthState() returns null when no signal has been
     *     saved, exactly as the real impl returns null when the
     *     "signaled_auth_name" key is absent.
     *   - clearSignaledAuthState() is total — both name and avatar keys
     *     are removed; the next read returns null.
     */
    private class FakePreferencesStorage : PreferencesStorage {
        private var account: Account? = null
        private var signaled: SignaledAuthState? = null

        override suspend fun saveAccount(account: Account) {
            this.account = account
        }

        override suspend fun getAccount(): Account? = account

        override suspend fun clearAccount() {
            account = null
        }

        override suspend fun saveSettings(settings: Settings) = Unit

        override suspend fun getSettings(): Settings = Settings(
            endpoint = Endpoint.Rutracker,
            theme = Theme.SYSTEM,
            favoritesSyncPeriod = SyncPeriod.OFF,
            bookmarksSyncPeriod = SyncPeriod.OFF,
        )

        override suspend fun getRatingLaunchCount(): Int = 0
        override suspend fun setRatingLaunchCount(count: Int) = Unit
        override suspend fun getRatingDisabled(): Boolean = false
        override suspend fun setRatingDisabled(value: Boolean) = Unit
        override suspend fun getRatingPostponed(): Boolean = false
        override suspend fun setRatingPostponed(value: Boolean) = Unit
        override suspend fun isOnboardingComplete(): Boolean = false
        override suspend fun setOnboardingComplete(value: Boolean) = Unit

        // Sweep Finding #9 closure (2026-05-17): the persistence test
        // suite does not exercise the observe path; emit an empty flow
        // so the contract is satisfied without affecting test semantics.
        override fun observeOnboardingComplete(): kotlinx.coroutines.flow.Flow<Boolean> =
            kotlinx.coroutines.flow.flowOf(false)

        override suspend fun getSignaledAuthState(): SignaledAuthState? = signaled

        override suspend fun saveSignaledAuthState(name: String, avatarUrl: String?) {
            signaled = SignaledAuthState(name = name, avatarUrl = avatarUrl)
        }

        override suspend fun clearSignaledAuthState() {
            signaled = null
        }

        override fun getDeviceId(): String = "fake-device-id-for-testing"

        private var historySyncPeriod: SyncPeriod = SyncPeriod.OFF
        private var credentialsSyncPeriod: SyncPeriod = SyncPeriod.OFF

        override fun getHistorySyncPeriod(): SyncPeriod = historySyncPeriod
        override fun setHistorySyncPeriod(period: SyncPeriod) { historySyncPeriod = period }
        override fun getCredentialsSyncPeriod(): SyncPeriod = credentialsSyncPeriod
        override fun setCredentialsSyncPeriod(period: SyncPeriod) { credentialsSyncPeriod = period }
    }

    /**
     * Stub [NetworkApi] — the persistence-layer tests in this file do not
     * exercise login, refreshToken, or any other network-dependent code
     * path, so every method throws to flag any unexpected wiring. If a
     * future test exercises the network seam, replace this with a
     * MockWebServer-backed instance per the boundary-only mock rule.
     */
    private class StubNetworkApi : NetworkApi {
        private fun unsupported(): Nothing =
            error("StubNetworkApi: unexpected network call — persistence tests must not hit the wire")

        override suspend fun checkAuthorized(token: String): Boolean = unsupported()
        override suspend fun login(
            username: String,
            password: String,
            captchaSid: String?,
            captchaCode: String?,
            captchaValue: String?,
        ): AuthResponseDto = unsupported()

        override suspend fun getFavorites(token: String): FavoritesDto = unsupported()
        override suspend fun addFavorite(token: String, id: String): Boolean = unsupported()
        override suspend fun removeFavorite(token: String, id: String): Boolean = unsupported()
        override suspend fun getForum(): ForumDto = unsupported()
        override suspend fun getCategory(id: String, page: Int?): CategoryPageDto = unsupported()
        override suspend fun getSearchPage(
            token: String,
            searchQuery: String?,
            categories: String?,
            author: String?,
            authorId: String?,
            sortType: SearchSortTypeDto?,
            sortOrder: SearchSortOrderDto?,
            period: SearchPeriodDto?,
            page: Int?,
        ): SearchPageDto = unsupported()

        override suspend fun getTopic(token: String, id: String, page: Int?): ForumTopicDto = unsupported()
        override suspend fun getTopicPage(token: String, id: String, page: Int?): TopicPageDto = unsupported()
        override suspend fun getCommentsPage(token: String, id: String, page: Int?): CommentsPageDto = unsupported()
        override suspend fun addComment(token: String, topicId: String, message: String): Boolean = unsupported()
        override suspend fun getTorrent(token: String, id: String): TorrentDto = unsupported()
        override suspend fun download(token: String, id: String): FileDto = unsupported()
    }
}
