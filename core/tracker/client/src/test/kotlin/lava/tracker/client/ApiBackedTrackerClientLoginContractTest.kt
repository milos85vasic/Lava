package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-stack contract test for the goapi-catalogue LOGIN path of
 * [ApiBackedTrackerClient] — the 2026-07-02 goapi keystone's device-proven
 * failure chain, guarded here at the fast unit layer.
 *
 * Two production defects the keystone surfaced, both covered:
 *  - **Fix C2 (Capability Honesty).** A descriptor declaring a non-NONE
 *    `authType` MUST expose the Authenticatable feature. Before the catalogue
 *    derived `AUTH_REQUIRED`, `getFeature<AuthenticatableTracker>()` returned
 *    null and `sdk.login()` short-circuited to null (no session cookie).
 *  - **Fix E (wire contract).** [LoginResultDto] MUST mirror the ACTUAL
 *    lava-api-go wire struct `provider.LoginResult`
 *    (`internal/provider/provider.go:218` — `{success, authToken, expiresAt}`).
 *    The prior `{state, sessionToken}` DTO threw
 *    `kotlinx.serialization.MissingFieldException: Field 'state' is required`
 *    when decoding the real 200 login response, so a genuinely-successful login
 *    surfaced to the user as "Connection test failed" and no session cookie was
 *    stored — every subsequent /v1/{id}/search then stayed anonymous / 401'd.
 *
 * Anti-Bluff posture (§6.J / Seventh Law clause 4):
 *  - The SUT ([ApiBackedTrackerClient]) is a REAL instance — never mocked.
 *  - The ONLY faked boundary is the network socket ([MockWebServer]).
 *  - Primary assertions are on user-visible / domain-observable state: whether
 *    login is Authenticated and the session cookie the client returns — never
 *    "mock was called".
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3):
 *  Reverting [LoginResultDto] to require a `state` field (the pre-Fix-E shape)
 *  makes [login_decodesRealGoapiWire_returnsAuthenticatedWithCookie] FAIL: the
 *  real `{success, authToken, expiresAt}` body cannot decode into a DTO that
 *  requires `state`, so login throws
 *  `kotlinx.serialization.MissingFieldException: Field 'state' is required for
 *  type with serial name 'lava.tracker.client.LoginResultDto'` — exactly the
 *  device crash at ApiBackedTrackerClient login → decodeFromString. Reverted: yes.
 */
class ApiBackedTrackerClientLoginContractTest {

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
    }

    /** A login-typed descriptor: authType requires login → AUTH_REQUIRED present. */
    private fun loginDescriptor() = RemoteTrackerDescriptor.from(
        trackerId = "rutracker",
        displayName = "RuTracker.org",
        capabilities = listOf("SEARCH", "AUTH_REQUIRED"),
        authType = "CAPTCHA_LOGIN",
        baseUrls = listOf("https://rutracker.org"),
        encoding = "windows-1251",
        supportsAnonymous = false,
    )

    private fun client(descriptor: RemoteTrackerDescriptor = loginDescriptor()) =
        ApiBackedTrackerClient(
            descriptor = descriptor,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = httpClient,
            authFieldName = "Lava-Auth",
            authKey = "k",
        )

    /**
     * Fix E — the load-bearing decode. The real lava-api-go 200 login response is
     * `{success, authToken, expiresAt}`; the client MUST decode it into an
     * Authenticated [lava.tracker.api.model.LoginResult] whose `sessionToken`
     * carries the upstream session cookie (so it can then be attached as
     * `Auth-Token` on searches).
     */
    @Test
    fun login_decodesRealGoapiWire_returnsAuthenticatedWithCookie() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "success": true,
                      "authToken": "bb_session=abc123def456",
                      "expiresAt": "2026-07-03T00:00:00Z"
                    }
                    """.trimIndent(),
                ),
        )

        val auth = client().getFeature(AuthenticatableTracker::class)
        assertNotNull(
            "A CAPTCHA_LOGIN descriptor MUST expose the Authenticatable feature (Fix C2)",
            auth,
        )
        val result = auth!!.login(LoginRequest(username = "u", password = "p"))

        // PRIMARY — the user-visible outcome: login succeeded AND the client
        // captured the session cookie it will replay as Auth-Token on searches.
        assertEquals(
            "a 200 {success:true,...} response MUST map to Authenticated",
            AuthState.Authenticated,
            result.state,
        )
        assertEquals(
            "the session cookie MUST be the wire authToken",
            "bb_session=abc123def456",
            result.sessionToken,
        )
    }

    /**
     * Fix E companion — a `{success:false}` 200 body maps to Unauthenticated with
     * no cookie (the server sends this shape; the client must not treat the
     * absence of a token as success).
     */
    @Test
    fun login_unsuccessfulWire_returnsUnauthenticatedWithNoCookie() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":false}"""),
        )

        val result = client().getFeature(AuthenticatableTracker::class)!!
            .login(LoginRequest(username = "u", password = "p"))

        assertEquals(AuthState.Unauthenticated, result.state)
        assertNull(result.sessionToken)
    }

    /**
     * Fix C2 client-layer contract — a NONE-auth descriptor MUST NOT expose the
     * Authenticatable feature (no AUTH_REQUIRED capability), so `sdk.login`
     * correctly returns null for anonymous providers.
     */
    @Test
    fun noAuthDescriptor_doesNotExposeAuthenticatable() {
        val noAuth = RemoteTrackerDescriptor.from(
            trackerId = "archiveorg",
            displayName = "Internet Archive",
            capabilities = listOf("SEARCH"),
            authType = "NONE",
            baseUrls = listOf("https://archive.org"),
            encoding = "UTF-8",
            supportsAnonymous = true,
        )
        assertNull(
            "a NONE-auth provider MUST NOT expose the login feature",
            client(noAuth).getFeature(AuthenticatableTracker::class),
        )
    }

    /**
     * The login POST reaches `/v1/rutracker/login` carrying the credentials —
     * the same request the device keystone issues. Confirms the client hits the
     * real contract route (not "mock was called").
     */
    @Test
    fun login_postsToV1LoginRoute() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"authToken":"bb_session=x"}"""),
        )

        client().getFeature(AuthenticatableTracker::class)!!
            .login(LoginRequest(username = "u", password = "p"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            "login MUST POST to /v1/rutracker/login — was ${recorded.path}",
            recorded.path?.endsWith("/v1/rutracker/login") == true,
        )
        assertEquals("k", recorded.getHeader("Lava-Auth"))
    }
}
