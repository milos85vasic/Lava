package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.tracker.api.AuthType
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.registry.DefaultTrackerRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0-1 end-to-end coverage for the PROVIDER LOGIN SESSION (`Auth-Token`) flow
 * (2026-06-14, master-plan P0-1 / search-residuals audit item 1).
 *
 * The 2026-06-14 audit found that the 5-layer fix repaired the per-endpoint
 * `Lava-Auth` key (so the request REACHES the server), but the dynamic
 * `ApiBackedTrackerClient.withAuth()` attached ONLY `Lava-Auth`, never the
 * provider login session `Auth-Token`. For an auth-required provider
 * (RuTracker / Kinozal) the server therefore got `Type:"none"` (anonymous) and
 * the upstream scrape returned login/empty. Search was fixed for no-auth
 * providers ONLY.
 *
 * This test pins the SECOND credential's full path:
 *
 *   login success → [ProviderSessionTokenHolder.set]\(provider, token\)
 *     → registry's apiClientFactory builds [ApiBackedTrackerClient] with
 *       `sessionToken = `[ProviderSessionTokenHolder.tokenFor]\(provider\)
 *         → `withAuth()` attaches `Auth-Token: {provider}:cookie:{token}`.
 *
 * It builds the registry with the SAME factory production uses
 * ([TrackerClientModule.provideTrackerRegistry]): reading the active base URL,
 * the per-endpoint `Lava-Auth` key, AND the per-provider session token. It
 * drives `set(provider, token)` → `populateFrom` → `registry.get(id)` →
 * `getFeature(Searchable).search()` and asserts the recorded request carries
 * BOTH headers on the wire (the §6.J user-visible-equivalent — the bytes the
 * server reads to decide authenticated-vs-anonymous + 200-vs-401), with the
 * `Auth-Token` value in the exact `provider:cookie:token` wire format
 * `lava-api-go/internal/auth/multiprovider.go` parses.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3):
 *   [authProvider_withoutSession_isAnonymous_andEmpties_theProductionBug] is the
 *   permanent discriminator — with NO session token stored (the exact pre-P0-1
 *   production state), the factory builds `sessionToken = null`, `withAuth()`
 *   attaches no `Auth-Token`, the dispatcher rejects the anonymous auth-provider
 *   request, and `search()` throws. Drop the `withAuth()` `Auth-Token` attach (or
 *   the factory's `sessionToken =` argument) and
 *   [authProviderSession_isThreaded_ontoTheAuthTokenHeader] FAILS — proving the
 *   header is load-bearing. The no-auth assertion
 *   ([noAuthProvider_attachesNoAuthTokenHeader]) proves the change is additive.
 */
class ProviderSessionTokenEndToEndWiringTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiBaseUrlHolder.reset()
        ProviderSessionTokenHolder.reset()
    }

    /**
     * Byte-for-byte equivalent to production's
     * [TrackerClientModule.provideTrackerRegistry] closure: reads the active base
     * URL, the per-endpoint `Lava-Auth` key, AND the per-provider session token.
     * The `sessionToken = ProviderSessionTokenHolder.tokenFor(...)` line is the
     * load-bearing addition this test guards.
     */
    private fun productionShapedRegistry(authFieldName: String): DefaultTrackerRegistry =
        DefaultTrackerRegistry().apply {
            setApiClientFactory { descriptor ->
                ApiBackedTrackerClient(
                    descriptor = descriptor,
                    apiBaseUrl = ApiBaseUrlHolder.current(),
                    httpClient = httpClient,
                    authFieldName = authFieldName,
                    authKey = ApiBaseUrlHolder.currentKey(),
                    sessionToken = ProviderSessionTokenHolder.tokenFor(descriptor.trackerId),
                )
            }
        }

    private fun searchDescriptor(id: String, authType: AuthType) = RemoteTrackerDescriptor(
        trackerId = id,
        displayName = id,
        baseUrls = listOf(lava.sdk.api.MirrorUrl(url = "https://$id.example", isPrimary = true)),
        capabilities = setOf(TrackerCapability.SEARCH),
        authType = authType,
        encoding = "UTF-8",
    )

    private val authFieldName = "Lava-Auth"
    private val authTokenHeader = "Auth-Token"

    /**
     * Server posture: the on-device api-app gates on `Lava-Auth`, and the
     * upstream tracker requires the provider session via `Auth-Token`. Here 200 +
     * a real result is returned ONLY when `Lava-Auth` matches AND a non-empty
     * `Auth-Token` is present; an authed provider WITHOUT `Auth-Token` (anonymous)
     * gets the empty/login 401 the real upstream returns.
     */
    private fun sessionGatedDispatcher(expectedKey: String) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val keyOk = request.getHeader(authFieldName) == expectedKey
                val sessionOk = request.getHeader(authTokenHeader)?.isNotBlank() == true
                return if (keyOk && sessionOk) {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {"provider":"rutracker","page":0,"totalPages":1,
                             "results":[{"id":"tt9","title":"Prince - Purple Rain",
                                         "seeders":42,
                                         "magnetLink":"magnet:?xt=urn:btih:PRINCE"}]}
                            """.trimIndent(),
                        )
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                }
            }
        }

    @Test
    fun authProviderSession_isThreaded_ontoTheAuthTokenHeader() = runTest {
        val key = "endpoint-key-7"
        // RuTracker's upstream login session value (the cookie the site issued).
        val session = "bb_session=0-12345-deadbeef"
        server.dispatcher = sessionGatedDispatcher(expectedKey = key)

        // 1. Onboarding step: active endpoint URL + its per-instance Lava-Auth key.
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), key)
        // 2. Login step: the provider login session lands in the holder.
        ProviderSessionTokenHolder.set("rutracker", session)

        // 3. DI-time factory install + catalogue populate (production order).
        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("rutracker", AuthType.CAPTCHA_LOGIN)))

        // 4. User taps search.
        val result = registry.get("rutracker", lava.sdk.api.MapPluginConfig())
            .getFeature(SearchableTracker::class)!!
            .search(SearchRequest(query = "prince"), page = 0)

        val recorded = server.takeRequest()

        // PRIMARY 1 — the byte on the wire: the holder-set session reached the
        // `Auth-Token` header in the EXACT `provider:cookie:token` wire format the
        // server's auth.ParseAuthToken parses into a cookie credential.
        assertEquals(
            "Auth-Token MUST carry the provider login session in the " +
                "{provider}:cookie:{token} format the server parses",
            "rutracker:cookie:$session",
            recorded.getHeader(authTokenHeader),
        )
        // The Lava-Auth key is still attached alongside (both credentials present).
        assertEquals(key, recorded.getHeader(authFieldName))
        assertEquals("/v1/rutracker/search?query=prince&page=0&sort=date&order=descending", recorded.path)

        // PRIMARY 2 — the user-visible outcome: real rows, NOT the 401 error.
        assertEquals(1, result.items.size)
        assertEquals("Prince - Purple Rain", result.items.single().title)
    }

    /**
     * CASE-COOKIE regression (2026-07-02): a session token stored AFTER the client
     * was already built MUST still reach the wire, because [withAuth] reads
     * [ProviderSessionTokenHolder] LIVE at request time. This is the exact
     * onboarding order the device keystone exposed: the ApiSelection step builds
     * the dynamic ApiBackedTrackerClient FIRST (holder empty → sessionToken=null),
     * then the Providers-step login stores the token. A build-time-only read left
     * the search anonymous → the Go API 401'd → "problem reaching the trackers".
     * Device evidence: .lava-ci-evidence/autonomous-qa/2026-07-02/goapi/.
     *
     * FALSIFIABILITY: revert [withAuth] to the build-time-only
     * `sessionToken?.let { ... }` (drop the live `ProviderSessionTokenHolder
     * .tokenFor(...)` read). Observed: this test FAILS — the built-before-login
     * client sends NO Auth-Token, the dispatcher 401s, and search() throws instead
     * of returning the row. Reverted: yes.
     */
    @Test
    fun sessionStoredAfterClientBuild_isStillThreaded_liveAtRequestTime() = runTest {
        val key = "endpoint-key-7"
        val session = "bb_session=0-47500467-late"
        server.dispatcher = sessionGatedDispatcher(expectedKey = key)

        // 1. Endpoint key present, but NO session yet — the client is about to be
        //    built anonymous (the onboarding ApiSelection-before-login order).
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), key)

        // 2. Build the dynamic client while the holder is EMPTY (sessionToken=null).
        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("rutracker", AuthType.CAPTCHA_LOGIN)))
        val searchable = registry.get("rutracker", lava.sdk.api.MapPluginConfig())
            .getFeature(SearchableTracker::class)!!

        // 3. LOGIN happens AFTER the client was built — token lands in the holder.
        ProviderSessionTokenHolder.set("rutracker", session)

        // 4. User taps search — withAuth() reads the holder LIVE, so the token IS
        //    threaded even though the client was constructed before it existed.
        val result = searchable.search(SearchRequest(query = "prince"), page = 0)
        val recorded = server.takeRequest()

        // PRIMARY 1 — the byte on the wire carries the late-stored session.
        assertEquals(
            "a session stored AFTER client build MUST be threaded onto Auth-Token " +
                "(withAuth reads the holder LIVE at request time — CASE-COOKIE fix)",
            "rutracker:cookie:$session",
            recorded.getHeader(authTokenHeader),
        )
        // PRIMARY 2 — user-visible outcome: real rows, NOT the 401 error.
        assertEquals(1, result.items.size)
        assertEquals("Prince - Purple Rain", result.items.single().title)
    }

    /**
     * The permanent falsifiability discriminator AND the in-test reproduction of
     * the PRE-P0-1 PRODUCTION BUG: an auth-required provider with NO stored login
     * session produces an ANONYMOUS request (no `Auth-Token`), the upstream
     * returns login/empty (401 here), and `search()` throws — exactly the
     * operator-reported "RuTracker/Kinozal search returns nothing".
     */
    @Test
    fun authProvider_withoutSession_isAnonymous_andEmpties_theProductionBug() = runTest {
        val key = "endpoint-key-7"
        server.dispatcher = sessionGatedDispatcher(expectedKey = key)

        // Endpoint key present (request reaches the server) but NO login session.
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), key)
        // ProviderSessionTokenHolder intentionally NOT set — the pre-P0-1 state.

        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("rutracker", AuthType.CAPTCHA_LOGIN)))
        val searchable = registry.get("rutracker", lava.sdk.api.MapPluginConfig())
            .getFeature(SearchableTracker::class)!!

        val thrown = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                searchable.search(SearchRequest(query = "prince"), page = 0)
            }
        }
        // PRIMARY — the user-visible failure path: the anonymous auth-provider
        // request is rejected (the upstream login/empty surfaced as 401 → throw).
        assertTrue(
            "anonymous auth-provider request MUST be rejected; observed: ${thrown.message}",
            thrown.message?.contains("HTTP 401") == true,
        )
        // And the wire confirms NO Auth-Token was attached (no session to thread).
        assertNull(server.takeRequest().getHeader(authTokenHeader))
    }

    /**
     * Additivity proof (§6.J): a NO-AUTH provider (Internet Archive / YTS curated)
     * attaches NO `Auth-Token` header — the no-auth search path is byte-for-byte
     * unchanged by the P0-1 session-token seam. Only the `Lava-Auth` key (the
     * endpoint key) is present, which is the no-auth behaviour that already works.
     */
    @Test
    fun noAuthProvider_attachesNoAuthTokenHeader() = runTest {
        val key = "endpoint-key-7"
        // No-auth dispatcher: 200 on the Lava-Auth key alone (no session needed).
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authFieldName) == key) {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"provider":"archiveorg","page":0,"totalPages":1,
                                "results":[{"id":"ia1","title":"Prince Live 1985"}]}""",
                        )
                } else {
                    MockResponse().setResponseCode(401)
                }
        }

        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), key)
        // A spurious session token for a DIFFERENT provider MUST NOT bleed onto
        // archiveorg's request — the holder is per-provider.
        ProviderSessionTokenHolder.set("rutracker", "bb_session=other")

        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("archiveorg", AuthType.NONE)))

        val result = registry.get("archiveorg", lava.sdk.api.MapPluginConfig())
            .getFeature(SearchableTracker::class)!!
            .search(SearchRequest(query = "prince"), page = 0)

        val recorded = server.takeRequest()
        // PRIMARY — the no-auth request carries NO Auth-Token (additive: unchanged).
        assertNull(
            "a no-auth provider MUST NOT carry an Auth-Token header — the no-auth " +
                "search path is unchanged by the session-token seam",
            recorded.getHeader(authTokenHeader),
        )
        assertEquals(key, recorded.getHeader(authFieldName))
        // And it still works (real row rendered).
        assertEquals(1, result.items.size)
        assertEquals("Prince Live 1985", result.items.single().title)
    }
}
