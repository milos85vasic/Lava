/*
 * Challenge Test C44 — API search attaches the per-endpoint Lava-Auth key, so
 * an auth-gated /v1/{provider}/search returns results instead of "Something
 * went wrong" (operator defect 2026-06-14; fix @fba19372).
 *
 * Operator-reported defect: searching ("prince") across RuTracker / YTS /
 * Kinozal via the on-device Android API failed with "Something went wrong,
 * please try again".
 *
 * ROOT CAUSE: the dynamic [ApiBackedTrackerClient] (the client that issues
 * `GET|POST /v1/{provider}/{op}` against the chosen lava-api-go / on-device
 * api-app) attached NO per-endpoint Lava-Auth key, so the auth-gated
 * `/v1/{provider}/search` returned HTTP 401. (It was also wired with the
 * strict system-trust OkHttp client instead of the permissive-TLS LAN client,
 * so the self-signed LAN cert failed the handshake — that TLS half is a wiring
 * concern proved by the DI/cold-start unit tests.) Either failure made
 * `getString` throw → `SearchResultViewModel` mapped the Throwable to
 * `error_something_goes_wrong`.
 *
 * FIX (@fba19372): the factory now passes `@Named("authFieldName")` + the
 * active endpoint's per-instance key (`ApiBaseUrlHolder.currentKey()`), and
 * [ApiBackedTrackerClient] attaches that key as the `Lava-Auth` header on EVERY
 * /v1 request (`withAuth()`).
 *
 * WHY THIS IS A DEVICE-RUN, REAL-STACK, USER-MEANINGFUL CHALLENGE (no bluff):
 * - The SUT is the REAL production [ApiBackedTrackerClient] — never mocked. The
 *   ONLY faked boundary is the network socket, replaced by a [MockWebServer]
 *   that runs INSIDE this instrumented test process ON THE DEVICE (so this is a
 *   genuine on-device execution, not a JVM unit test).
 * - The server's [Dispatcher] models the EXACT production auth gate: it returns
 *   HTTP 401 for any request WITHOUT `Lava-Auth: k`, and a REAL lava-api-go
 *   SearchResult JSON body for a request WITH it. So passing requires the
 *   client to actually put the key on the wire — exactly what the fix added.
 * - PRIMARY assertions are on user-visible / wire-observable state per §6.AB
 *   clause 1 + Sixth Law clause 3: (1) the parsed domain result a user would
 *   SEE in SearchResultScreen (a real result item titled "Prince — Greatest
 *   Hits"), NOT the "Something went wrong" error; and (2) the recorded
 *   `Lava-Auth` header value the client put on the wire. Never "a mock was
 *   called N times".
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing → the production bug):
 *
 *   1. In ApiBackedTrackerClient remove `.withAuth()` from the request builders
 *      (or pass authKey=null at the factory) — the production state BEFORE the
 *      fix. The client still composes and issues the request; it just omits the
 *      Lava-Auth header.
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: the keyless request is 401'd by the dispatcher →
 *      `getString` throws `IllegalStateException("API request failed: HTTP 401
 *      …")` → `search_attachesPerEndpointAuthKey_returnsRealResult` fails with
 *      that exception (the user-facing analogue of "Something went wrong").
 *   4. Revert; re-run; the key is on the wire, search returns the real result,
 *      and the test passes.
 *
 *   The discriminator `search_withoutAuthKey_throwsOnAuthGatedApi` proves the
 *   key is LOAD-BEARING: a keyless client against the SAME auth-gated server
 *   throws — so the passing case above genuinely depends on the attached key,
 *   not on a permissive server.
 *
 * Honest scope: this Challenge is the DETERMINISTIC on-device proof of the
 * auth-gate behaviour (the client attaches the per-endpoint key, so an
 * auth-gated API returns results). The full VISUAL search-results UI flow
 * (Onboarding → ApiSelection → Providers → Search → SearchResult driving the
 * real Compose UI against a running api-app over the network) is an e2e flow
 * that needs a live backend and is tracked separately via the §6.AE Dynamic
 * Provider Discovery Challenges (C39) + the HelixQA video pass. The MockWebServer
 * here stands in for that live backend deterministically, on-device.
 *
 * // covers-feature: search
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge44ApiSearchAuthTest"
 */
package lava.app.challenges

import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.client.ApiBackedTrackerClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's Espresso/Compose-on-API36 incident (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json). Kept on every Challenge for matrix consistency even when this one drives no Compose UI.
class Challenge44ApiSearchAuthTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    // The Lava-Auth header NAME comes from config in production (§6.R — never
    // hardcoded). The on-device MockWebServer is our own boundary here, so the
    // canonical name is supplied to BOTH the client and the dispatcher gate;
    // they must agree, which is exactly what production wiring guarantees.
    private val authFieldName = "Lava-Auth"
    private val endpointKey = "k"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A descriptor that declares SEARCH (the capability under test). */
    private fun searchDescriptor() = RemoteTrackerDescriptor.from(
        trackerId = "rutracker",
        displayName = "RuTracker.org",
        capabilities = listOf("SEARCH", "TORRENT_DOWNLOAD"),
        authType = "NONE",
        baseUrls = listOf("https://rutracker.org"),
        encoding = "UTF-8",
        supportsAnonymous = true,
    )

    private fun client(authKey: String?) = ApiBackedTrackerClient(
        descriptor = searchDescriptor(),
        apiBaseUrl = server.url("/").toString().trimEnd('/'),
        httpClient = httpClient,
        authFieldName = authFieldName,
        authKey = authKey,
    )

    // CHALLENGE: against an auth-gated API that 401s without the Lava-Auth key,
    // the client carrying the per-endpoint key gets a REAL result — the user
    // sees a search result, NOT "Something went wrong". Primary on the parsed
    // result a user would see + the key on the wire.
    @Test
    fun search_attachesPerEndpointAuthKey_returnsRealResult() = runBlocking {
        // The EXACT production auth gate: 401 without the key, a real
        // lava-api-go SearchResult JSON with it.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authFieldName) == endpointKey) {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {"provider":"rutracker","page":0,"totalPages":1,
                             "results":[{"id":"1","title":"Prince — Greatest Hits",
                                         "sizeBytes":1,"seeders":7,"leechers":0,
                                         "magnetLink":"magnet:?xt=urn:btih:AB",
                                         "downloadUrl":"https://x/1.torrent",
                                         "infoHash":"AB","category":"Music"}]}
                            """.trimIndent(),
                        )
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                }
        }

        val searchable = client(authKey = endpointKey)
            .getFeature(SearchableTracker::class)!!
        val result = searchable.search(SearchRequest(query = "prince"), page = 0)

        // PRIMARY 1 (user-visible): a real result row — search SUCCEEDED, the
        // user does NOT see "Something went wrong".
        assertEquals(
            "The user MUST get a real search result (not the 'Something went wrong' error)",
            1,
            result.items.size,
        )
        assertEquals("Prince — Greatest Hits", result.items.single().title)

        // PRIMARY 2 (wire-observable): the client attached the per-endpoint key.
        assertEquals(
            "ApiBackedTrackerClient MUST send the per-endpoint Lava-Auth key on the search request",
            endpointKey,
            server.takeRequest().getHeader(authFieldName),
        )
    }

    // DISCRIMINATOR: WITHOUT the key, the auth-gated API 401s and search throws
    // — exactly the production failure before the fix. Proves the key is
    // load-bearing (the passing case above genuinely depends on it).
    @Test(expected = IllegalStateException::class)
    fun search_withoutAuthKey_throwsOnAuthGatedApi() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authFieldName) == endpointKey) {
                    MockResponse().setBody("""{"provider":"rutracker","page":0,"totalPages":0,"results":[]}""")
                } else {
                    MockResponse().setResponseCode(401)
                }
        }

        client(authKey = null) // no key → unauthenticated, the pre-fix state
            .getFeature(SearchableTracker::class)!!
            .search(SearchRequest(query = "prince"), page = 0)
        Unit
    }
}
