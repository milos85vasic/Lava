package lava.tracker.rutracker.feature

import kotlinx.coroutines.runBlocking
import lava.auth.api.TokenProvider
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.SearchRequest
import lava.tracker.rutracker.RuTrackerHttpClientFactory
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.GetCurrentProfileUseCase
import lava.tracker.rutracker.domain.GetProfileUseCase
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.domain.LoginUseCase
import lava.tracker.rutracker.domain.LogoutUseCase
import lava.tracker.rutracker.domain.VerifyAuthorisedUseCase
import lava.tracker.rutracker.domain.VerifyTokenUseCase
import lava.tracker.rutracker.domain.WithAuthorisedCheckUseCase
import lava.tracker.rutracker.domain.WithTokenVerificationUseCase
import lava.tracker.rutracker.impl.RuTrackerInnerApiImpl
import lava.tracker.rutracker.magnet.RuTrackerMagnetCache
import lava.tracker.rutracker.mapper.AuthMapper
import lava.tracker.rutracker.mapper.SearchPageMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduce-first regression test for the 2026-07-02 LAYER 2 defect
 * (goapi-keystone LAYER 2 — bundled RuTrackerClient search short-circuits
 * Unauthorized because the SDK login path never wrote the session token to the
 * store [TokenProvider.getToken] reads).
 *
 * DEFECT: after a genuinely-successful multi-tracker SDK login
 * ([RuTrackerAuth.login] -> [LoginUseCase]), [RuTrackerSearch.search] read an
 * EMPTY token from the [TokenProvider] store, so [WithTokenVerificationUseCase]
 * ([VerifyTokenUseCase] = token.isNotEmpty()) threw `Unauthorized` BEFORE any
 * `tracker.php` HTTP request was issued. The user saw "problem reaching the
 * trackers" with NO request on the wire, even though the login succeeded and the
 * bundled Ktor client held a valid session cookie. Token-store mismatch: only
 * the legacy `AuthServiceImpl.login` wrote the token; the SDK path never did.
 *
 * This test drives the REAL production classes end-to-end — the same surfaces
 * the user touches (§6.J Sixth Law clause 1): real [RuTrackerAuth] +
 * [LoginUseCase] for login, real [RuTrackerSearch] + [GetSearchPageUseCase] +
 * [WithTokenVerificationUseCase] + [VerifyTokenUseCase] for search, real
 * [RuTrackerInnerApiImpl] over a [MockWebServer]. The ONLY boundary fake is the
 * [TokenProvider] store — [InMemoryTokenProvider] is behaviorally equivalent to
 * the production `AuthServiceImpl` token store (getToken returns "" until a
 * token is persisted; persistProviderToken stores a non-blank token). The SAME
 * provider instance is shared by login and search, exactly as the Hilt singleton
 * (`AuthServiceImpl`) is shared in production.
 *
 * PRIMARY assertion (§6.J clause 3, user-visible state): the returned
 * [lava.tracker.api.model.SearchResult] carries the parsed torrent row (id +
 * title). Secondary: exactly one `tracker.php?nm=` request reached the wire.
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / §6.N):
 *   Mutation: remove the `tokenProvider.persistProviderToken(...)` bridge in
 *             [RuTrackerAuth.login] (revert the LAYER 2 fix).
 *   Observed-Failure: the test FAILS with
 *             `failure message="Unauthorized" type="lava.tracker.rutracker.model.Unauthorized"`.
 *             getToken() returns "" so [VerifyTokenUseCase] is false and
 *             [WithTokenVerificationUseCase] throws `Unauthorized` BEFORE
 *             `api.search()` runs — trackerPhpHits stays 0. (The reported stack
 *             is the `data object Unauthorized` singleton's cached <clinit>
 *             stack — a JVM artifact of `data object : Throwable`; the real
 *             throw site is the empty-token guard.)
 *   Reverted: yes.
 */
class RuTrackerSearchAfterSdkLoginTest {

    private lateinit var server: MockWebServer
    private val trackerPhpHits = AtomicInteger(0)

    private val loggedInMarker =
        """<a id="logged-in-username" href="./profile.php?mode=viewprofile&u=12345">tester</a>"""

    // A minimal-but-real logged-in index page: carries the profile link
    // GetCurrentProfileUseCase needs (u=12345) AND the "logged-in-username"
    // marker VerifyAuthorisedUseCase gates on.
    private val loggedInIndexHtml = """
        <!DOCTYPE html><html><head><title>RuTracker.org</title></head><body>
        $loggedInMarker
        </body></html>
    """.trimIndent()

    // A minimal search-results page with one .hl-tr row shaped exactly as
    // GetSearchPageUseCase.parseSearchPage expects (.t-title>a[data-topic_id],
    // .u-name>a, .f[href?f=], .tor-size[data-ts_text]). Also carries the
    // logged-in marker so WithAuthorisedCheckUseCase does not reject it.
    private val searchResultsHtml = """
        <!DOCTYPE html><html><head><title>tracker</title></head><body>
        $loggedInMarker
        <table id="tor-tbl"><tbody>
        <tr class="hl-tr">
          <td class="t-title"><a data-topic_id="777" href="./viewtopic.php?t=777">The Matrix 1999 [1080p BDRip]</a></td>
          <td class="u-name"><a href="./tracker.php?pid=555">uploader</a></td>
          <td><a class="f" href="./tracker.php?f=2000">Movies</a></td>
          <td class="tor-size" data-ts_text="1234567">1.2 MB</td>
          <td class="seedmed">42</td>
          <td class="leechmed">3</td>
        </tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("login.php") ->
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Set-Cookie", "bb_session=SEARCH-SESSION-XYZ")
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(loggedInIndexHtml)

                    path.contains("tracker.php") -> {
                        trackerPhpHits.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(searchResultsHtml)
                    }

                    path.contains("profile.php") ->
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("<html><body></body></html>")

                    // index.php — login preflight + mainPage (profile id source).
                    else ->
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(loggedInIndexHtml)
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** In-memory store behaviorally equivalent to the production AuthServiceImpl token store. */
    private class InMemoryTokenProvider : TokenProvider {
        @Volatile
        private var stored: String = ""

        override suspend fun getToken(): String = stored

        override suspend fun refreshToken(): Boolean = false

        override suspend fun persistProviderToken(token: String) {
            if (token.isNotEmpty()) stored = token
        }
    }

    @Test
    fun `search after a successful SDK login issues tracker_php and returns parsed results`() = runBlocking {
        val client = RuTrackerHttpClientFactory.create(server.url("/forum/").toString())
        val api = RuTrackerInnerApiImpl(client)

        // ONE shared token store — exactly like the Hilt singleton AuthServiceImpl
        // that production injects into BOTH RuTrackerAuth and RuTrackerSearch.
        val tokenProvider = InMemoryTokenProvider()

        // Real login graph.
        val getProfileUseCase = GetProfileUseCase(api)
        val getCurrentProfileUseCase = GetCurrentProfileUseCase(api, getProfileUseCase)
        val loginUseCase = LoginUseCase(api, getCurrentProfileUseCase)
        val checkAuthorisedUseCase = CheckAuthorisedUseCase(api, VerifyAuthorisedUseCase)
        val rutrackerAuth = RuTrackerAuth(
            loginUseCase = loginUseCase,
            logoutUseCase = LogoutUseCase(tokenProvider),
            checkUseCase = checkAuthorisedUseCase,
            mapper = AuthMapper(),
            tokenProvider = tokenProvider,
        )

        // Real search graph.
        val getSearchPageUseCase = GetSearchPageUseCase(
            api = api,
            withTokenVerificationUseCase = WithTokenVerificationUseCase(VerifyTokenUseCase),
            withAuthorisedCheckUseCase = WithAuthorisedCheckUseCase(VerifyAuthorisedUseCase),
        )
        val rutrackerSearch = RuTrackerSearch(
            getSearchPage = getSearchPageUseCase,
            mapper = SearchPageMapper(),
            tokenProvider = tokenProvider,
            magnetCache = RuTrackerMagnetCache(),
        )

        // 1. SDK-path login (the real production method that had the bug).
        rutrackerAuth.login(LoginRequest(username = "tester", password = "pw", captcha = null))

        // 2. Search — pre-fix this THROWS Unauthorized here (RED); the guard
        //    short-circuits before any tracker.php request.
        val result = rutrackerSearch.search(SearchRequest(query = "matrix"), page = 1)

        // Secondary (on the wire): exactly one tracker.php request was issued.
        assertEquals(
            "search must issue exactly one tracker.php request after a successful SDK login " +
                "(pre-fix: 0 — the empty-token guard short-circuits Unauthorized before the request)",
            1,
            trackerPhpHits.get(),
        )

        // PRIMARY (user-visible SearchResult): the parsed torrent row is returned.
        assertTrue(
            "search must return the parsed torrent row; items=${result.items}",
            result.items.isNotEmpty(),
        )
        assertEquals(
            "parsed torrent id (user-visible result row)",
            "777",
            result.items.first().torrentId,
        )
        assertEquals(
            "parsed torrent title (user-visible result row; tags stripped)",
            "The Matrix 1999",
            result.items.first().title,
        )
        assertEquals(
            "parsed seeders (user-visible result row)",
            42,
            result.items.first().seeders,
        )
    }
}
