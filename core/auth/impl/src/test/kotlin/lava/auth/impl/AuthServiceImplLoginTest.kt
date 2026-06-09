package lava.auth.impl

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.auth.AuthResult
import lava.models.auth.AuthState
import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.network.api.NetworkApi
import lava.network.dto.FileDto
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.CaptchaDto
import lava.network.dto.auth.UserDto
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack tests for [AuthServiceImpl.login], [AuthServiceImpl.refreshToken]
 * and [AuthServiceImpl.getToken].
 *
 * Anti-Bluff posture (§6.J / Sixth Law):
 *   - The SUT is the production [AuthServiceImpl]; it is NEVER mocked.
 *   - The only fakes are at the boundaries BELOW the SUT: a configurable
 *     [FakeNetworkApi] standing in for the HTTP seam (the wire), and a
 *     behaviorally-equivalent [FakePreferencesStorage] standing in for
 *     SharedPreferences (separate account vs signaled-auth keyspaces,
 *     null on absent key).
 *   - Primary assertions are on user-visible state: the [AuthResult] the
 *     UI renders, the persisted [Account] read back from storage, and the
 *     [AuthState] emitted into the SharedFlow the Search/Forum screens
 *     observe.
 *
 * Bluff-Audit recorded in the commit body.
 */
class AuthServiceImplLoginTest {

    @Test
    fun `login Success persists account and emits Authorized`() = runTest {
        val storage = FakePreferencesStorage()
        val api = FakeNetworkApi(
            loginResponse = AuthResponseDto.Success(
                UserDto(id = "42", token = "tok-xyz", avatarUrl = "http://example.test/a.png"),
            ),
        )
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val result = service.login("alice", "secret", null, null, null)

        // Primary (user-visible result): the login succeeds
        assertEquals(AuthResult.Success, result)
        // Primary (persisted state): the account is saved with the right token,
        // username taken from the login form (not the DTO) and avatar from the DTO
        assertEquals(
            Account(id = "42", name = "alice", password = "secret", token = "tok-xyz", avatarUrl = "http://example.test/a.png"),
            storage.getAccount(),
        )
        // getToken() now returns the persisted token the HTTP auth layer needs
        assertEquals("tok-xyz", service.getToken())
        // The Search/Forum screens observe Authorized
        assertEquals(
            AuthState.Authorized(name = "alice", avatarUrl = "http://example.test/a.png"),
            service.observeAuthState().first(),
        )
    }

    @Test
    fun `login WrongCredits maps to WrongCredits without persisting an account`() = runTest {
        val storage = FakePreferencesStorage()
        val api = FakeNetworkApi(loginResponse = AuthResponseDto.WrongCredits(captcha = null))
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val result = service.login("alice", "wrong", null, null, null)

        assertEquals(AuthResult.WrongCredits(captcha = null), result)
        // No account persisted — getToken stays empty, observers stay Unauthorized
        assertNull(storage.getAccount())
        assertEquals("", service.getToken())
        assertEquals(AuthState.Unauthorized, service.observeAuthState().first())
    }

    @Test
    fun `login WrongCredits carries the captcha through the DTO mapping`() = runTest {
        val storage = FakePreferencesStorage()
        val api = FakeNetworkApi(
            loginResponse = AuthResponseDto.WrongCredits(
                captcha = CaptchaDto(id = "sid-1", code = "cap_code", url = "http://example.test/cap.png"),
            ),
        )
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val result = service.login("alice", "wrong", null, null, null)

        result as AuthResult.WrongCredits
        // The DTO→domain captcha mapping (id/code/url) is the user-visible payload
        // the login screen renders to let the user retype the captcha.
        assertEquals("sid-1", result.captcha?.id)
        assertEquals("cap_code", result.captcha?.code)
        assertEquals("http://example.test/cap.png", result.captcha?.url)
    }

    @Test
    fun `login CaptchaRequired maps DTO captcha to a required domain captcha`() = runTest {
        val storage = FakePreferencesStorage()
        val api = FakeNetworkApi(
            loginResponse = AuthResponseDto.CaptchaRequired(
                captcha = CaptchaDto(id = "sid-2", code = "abc", url = "http://example.test/c2.png"),
            ),
        )
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val result = service.login("alice", "secret", null, null, null)

        result as AuthResult.CaptchaRequired
        assertEquals("sid-2", result.captcha.id)
        assertEquals("abc", result.captcha.code)
        assertEquals("http://example.test/c2.png", result.captcha.url)
        // CaptchaRequired must NOT persist an account
        assertNull(storage.getAccount())
    }

    @Test
    fun `login ServiceUnavailable maps to ServiceUnavailable with the verbatim reason`() = runTest {
        val storage = FakePreferencesStorage()
        val api = FakeNetworkApi(
            loginResponse = AuthResponseDto.ServiceUnavailable(reason = "CloudflareBlocked: 503"),
        )
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val result = service.login("alice", "secret", null, null, null)

        // The §6.J anti-bluff distinction: the user MUST see the service-error
        // reason, NOT "Wrong credentials".
        assertEquals(AuthResult.ServiceUnavailable("CloudflareBlocked: 503"), result)
        assertNull(storage.getAccount())
    }

    @Test
    fun `refreshToken on success rotates the persisted token and returns true`() = runTest {
        val storage = FakePreferencesStorage().apply {
            saveAccount(Account(id = "1", name = "bob", password = "pw", token = "old-token", avatarUrl = null))
        }
        val api = FakeNetworkApi(
            loginResponse = AuthResponseDto.Success(
                UserDto(id = "1", token = "fresh-token", avatarUrl = "http://example.test/b.png"),
            ),
        )
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val refreshed = service.refreshToken()

        assertTrue(refreshed)
        // The persisted token is rotated to the freshly-issued one; identity
        // fields are preserved (same account, only the token changes).
        val account = storage.getAccount()!!
        assertEquals("fresh-token", account.token)
        assertEquals("bob", account.name)
        assertEquals("pw", account.password)
        assertEquals("fresh-token", service.getToken())
    }

    @Test
    fun `refreshToken with no account logs out and returns false`() = runTest {
        val storage = FakePreferencesStorage()
        val api = FakeNetworkApi(
            loginResponse = AuthResponseDto.Success(UserDto("1", "t", "a")),
        )
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val refreshed = service.refreshToken()

        assertFalse(refreshed)
        // No login attempt should have been made when there is no account
        assertEquals(0, api.loginCallCount)
        assertNull(storage.getAccount())
        assertEquals(AuthState.Unauthorized, service.observeAuthState().first())
    }

    @Test
    fun `refreshToken when upstream rejects the stored credentials logs out and returns false`() = runTest {
        val storage = FakePreferencesStorage().apply {
            saveAccount(Account(id = "1", name = "bob", password = "stale", token = "t", avatarUrl = null))
            saveSignaledAuthState(name = "bob", avatarUrl = null)
        }
        val api = FakeNetworkApi(loginResponse = AuthResponseDto.WrongCredits(captcha = null))
        val service = AuthServiceImpl(api = api, preferencesStorage = storage)

        val refreshed = service.refreshToken()

        assertFalse(refreshed)
        // logout() cleared both the account and the signaled-auth signal
        assertNull(storage.getAccount())
        assertNull(storage.getSignaledAuthState())
    }

    /**
     * Configurable [NetworkApi] boundary fake. Only [login] is exercised by
     * these tests; every other method throws to flag unexpected wiring. The
     * login response is injected so each test drives a specific DTO branch
     * through the real [AuthServiceImpl.login] mapping.
     */
    private class FakeNetworkApi(
        private val loginResponse: AuthResponseDto,
    ) : NetworkApi {
        var loginCallCount = 0
            private set

        private fun unsupported(): Nothing =
            error("FakeNetworkApi: unexpected network call")

        override suspend fun checkAuthorized(token: String): Boolean = unsupported()

        override suspend fun login(
            username: String,
            password: String,
            captchaSid: String?,
            captchaCode: String?,
            captchaValue: String?,
        ): AuthResponseDto {
            loginCallCount++
            return loginResponse
        }

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

    /**
     * Behaviorally-equivalent fake of [lava.securestorage.PreferencesStorageImpl]
     * (Anti-Bluff Pact Third Law). Account and signaled-auth keyspaces are
     * separate; absent reads return null; clearing one keyspace does not touch
     * the other. Mirrors the fake in [AuthServiceImplPersistenceTest].
     */
    private class FakePreferencesStorage : PreferencesStorage {
        private var account: Account? = null
        private var signaled: SignaledAuthState? = null

        override suspend fun saveAccount(account: Account) { this.account = account }
        override suspend fun getAccount(): Account? = account
        override suspend fun clearAccount() { account = null }

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
        override fun observeOnboardingComplete(): kotlinx.coroutines.flow.Flow<Boolean> =
            kotlinx.coroutines.flow.flowOf(false)

        override suspend fun getSignaledAuthState(): SignaledAuthState? = signaled
        override suspend fun saveSignaledAuthState(name: String, avatarUrl: String?) {
            signaled = SignaledAuthState(name = name, avatarUrl = avatarUrl)
        }
        override suspend fun clearSignaledAuthState() { signaled = null }

        override fun getDeviceId(): String = "fake-device-id-for-testing"

        private var historySyncPeriod: SyncPeriod = SyncPeriod.OFF
        private var credentialsSyncPeriod: SyncPeriod = SyncPeriod.OFF
        override fun setHistorySyncPeriod(period: SyncPeriod) { historySyncPeriod = period }
        override fun setCredentialsSyncPeriod(period: SyncPeriod) { credentialsSyncPeriod = period }
    }
}
