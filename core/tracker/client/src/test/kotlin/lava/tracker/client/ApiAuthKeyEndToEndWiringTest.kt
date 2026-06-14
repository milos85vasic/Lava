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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §6.N end-to-end coverage for the FULL Lava-Auth key flow (bluff-hunt 2026-06-14).
 *
 * The 2026-06-14 "search → 401 → Something went wrong" production defect lives in
 * the 5-layer key wiring:
 *
 *   onboarding reads key → [ApiBaseUrlHolder.set]\(url, key\)
 *     → registry's apiClientFactory builds [ApiBackedTrackerClient] with
 *       `authKey = `[ApiBaseUrlHolder.currentKey]\(\)
 *         → [ApiBackedTrackerClient]'s `withAuth()` attaches `Lava-Auth: key`.
 *
 * **Why this test exists — the gap the existing tests left (the PARTIAL BLUFF).**
 * [ApiBackedTrackerClientTest.search_attachesPerEndpointAuthKey_soAuthGatedApiReturnsResults]
 * passes — but it constructs `ApiBackedTrackerClient(authKey = "k")` DIRECTLY,
 * bypassing the `ApiBaseUrlHolder → factory` path entirely. It proves `withAuth()`
 * in isolation; it does NOT prove the holder-set key is THREADED THROUGH the
 * factory onto the wire. [DynamicRegistryRealClientTest] DOES drive the
 * holder→factory→registry path, but its `registryWithRealBuilder` factory reads
 * only [ApiBaseUrlHolder.current] and OMITS the `authKey = currentKey()` argument —
 * so it never exercises the key. Result: every existing test was green while the
 * real end-to-end key flow (the thing that 401'd on device) was UNTESTED.
 *
 * This test closes that gap. It builds the registry with the SAME factory
 * production uses ([TrackerClientModule.provideTrackerRegistry], lines 307-315):
 * reading BOTH `ApiBaseUrlHolder.current()` AND `ApiBaseUrlHolder.currentKey()`.
 * It drives `set(url, key)` → `populateFrom` → `registry.get(id)` →
 * `getFeature(Searchable).search()` and asserts the recorded request carries
 * `Lava-Auth: key` on the wire — the §6.J user-visible-equivalent (the byte on the
 * socket the auth-gated api-app reads to decide 200-vs-401).
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3):
 *   With `ApiBaseUrlHolder.set(url, null)` (the holder NEVER given a key — exactly
 *   the production ordering bug where onboarding fails to pass the key), the
 *   factory builds `authKey = currentKey() = null`, `withAuth()` attaches nothing,
 *   the MockWebServer dispatcher 401s the keyless request, and `search()` throws
 *   `IllegalStateException("API request failed: HTTP 401 …")`. The
 *   [search_withoutHolderKey_401sAndThrows_theProductionBug] test pins that — it
 *   IS the deliberate-break rehearsal, kept as a permanent discriminator.
 */
class ApiAuthKeyEndToEndWiringTest {

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
    }

    /**
     * Builds the registry with the factory that is BYTE-FOR-BYTE equivalent to
     * production's [TrackerClientModule.provideTrackerRegistry] closure: it reads
     * the active base URL AND the per-endpoint key from [ApiBaseUrlHolder]. This
     * is the load-bearing difference from [DynamicRegistryRealClientTest], whose
     * factory omits `authKey = ApiBaseUrlHolder.currentKey()`.
     */
    private fun productionShapedRegistry(authFieldName: String): DefaultTrackerRegistry =
        DefaultTrackerRegistry().apply {
            setApiClientFactory { descriptor ->
                ApiBackedTrackerClient(
                    descriptor = descriptor,
                    apiBaseUrl = ApiBaseUrlHolder.current(),
                    httpClient = httpClient,
                    authFieldName = authFieldName,
                    // The line the existing real-stack test forgot — the whole point.
                    authKey = ApiBaseUrlHolder.currentKey(),
                )
            }
        }

    private fun searchDescriptor(id: String) = RemoteTrackerDescriptor(
        trackerId = id,
        displayName = id,
        baseUrls = listOf(lava.sdk.api.MirrorUrl(url = "https://$id.example", isPrimary = true)),
        capabilities = setOf(TrackerCapability.SEARCH),
        authType = AuthType.NONE,
        encoding = "UTF-8",
    )

    /**
     * Auth-gated dispatcher: 200 + a real result ONLY when `Lava-Auth` matches the
     * expected key; 401 otherwise. This is the on-device api-app's actual posture.
     */
    private fun authGatedDispatcher(authFieldName: String, expectedKey: String) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authFieldName) == expectedKey) {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {"provider":"jackett-1337x","page":0,"totalPages":1,
                             "results":[{"id":"tt7","title":"Sintel","seeders":9,
                                         "magnetLink":"magnet:?xt=urn:btih:SINTEL"}]}
                            """.trimIndent(),
                        )
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                }
        }

    @Test
    fun holderKey_isThreadedThroughFactory_ontoTheLavaAuthHeader() = runTest {
        // §6.R: the header NAME is config-injected in production; tests use a
        // synthetic literal (permitted — not a real production value).
        val authFieldName = "Lava-Auth"
        val key = "endpoint-key-7"
        server.dispatcher = authGatedDispatcher(authFieldName, key)

        // 1. Onboarding step: the active endpoint URL + its per-instance key.
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), key)

        // 2. DI-time factory install + catalogue populate (production order).
        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("jackett-1337x")))

        // 3. User taps search: resolve the live client and issue the request.
        val client = registry.get("jackett-1337x", lava.sdk.api.MapPluginConfig())
        assertTrue("resolved client is the API-backed one", client is ApiBackedTrackerClient)
        val result = client.getFeature(SearchableTracker::class)!!
            .search(SearchRequest(query = "sintel"), page = 0)

        // PRIMARY 1 — the byte on the wire: the holder-set key reached the header.
        val recorded = server.takeRequest()
        assertEquals(
            "the Lava-Auth header MUST carry the holder-set per-endpoint key — " +
                "this is the byte the auth-gated api-app reads to return 200 vs 401",
            key,
            recorded.getHeader(authFieldName),
        )
        assertEquals("/v1/jackett-1337x/search?query=sintel&page=0", recorded.path)

        // PRIMARY 2 — the user-visible outcome: a real result, NOT the 401 error.
        assertEquals(1, result.items.size)
        assertEquals("Sintel", result.items.single().title)
    }

    /**
     * The permanent falsifiability discriminator AND the in-test reproduction of
     * the PRODUCTION ORDERING BUG: when the holder is given a URL but NO key
     * (`set(url, null)` — onboarding never threaded the key), the
     * production-shaped factory reads `currentKey() == null`, builds a keyless
     * client, the api-app 401s, and the user-facing `search()` throws.
     *
     * If `withAuth()` were ever changed to attach the key unconditionally (or the
     * factory hard-coded a key), this test would PASS the 401 dispatcher and FAIL —
     * which is the point: it proves the key is load-bearing.
     */
    @Test
    fun search_withoutHolderKey_401sAndThrows_theProductionBug() = runTest {
        val authFieldName = "Lava-Auth"
        server.dispatcher = authGatedDispatcher(authFieldName, expectedKey = "the-real-key")

        // Holder set WITHOUT a key — the production ordering defect.
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), null)

        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("jackett-1337x")))
        val searchable = registry.get("jackett-1337x", lava.sdk.api.MapPluginConfig())
            .getFeature(SearchableTracker::class)!!

        val thrown = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                searchable.search(SearchRequest(query = "sintel"), page = 0)
            }
        }
        // PRIMARY — the user-visible failure path: HTTP 401 surfaces as the throw
        // that the app maps to the generic "Something went wrong".
        assertTrue(
            "keyless request MUST 401; observed: ${thrown.message}",
            thrown.message?.contains("HTTP 401") == true,
        )

        // And the wire confirms NO key was attached (the holder had none to thread).
        assertEquals(null, server.takeRequest().getHeader(authFieldName))
    }

    /**
     * Reproduces the OTHER production ordering hazard the operator flagged: the
     * client built BEFORE the key was set. [ApiBaseUrlHolder.currentKey] is read
     * inside the factory closure at `registry.get()` (build) time, NOT at
     * `setApiClientFactory` install time — so a key set AFTER the factory install
     * but BEFORE the user searches IS picked up. This proves the holder read is
     * correctly LATE-BOUND (build-time), eliminating the "client built before set"
     * timing bug as a root cause for the holder→factory hop.
     */
    @Test
    fun keySetAfterFactoryInstall_butBeforeSearch_isPickedUp_lateBinding() = runTest {
        val authFieldName = "Lava-Auth"
        val key = "late-bound-key"
        server.dispatcher = authGatedDispatcher(authFieldName, key)

        // Factory installed FIRST (DI time), before any endpoint/key is known.
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), null)
        val registry = productionShapedRegistry(authFieldName)
        registry.populateFrom(listOf(searchDescriptor("jackett-1337x")))

        // THEN onboarding completes and sets the key — AFTER the factory install
        // and AFTER populateFrom, but BEFORE the user searches.
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'), key)

        val result = registry.get("jackett-1337x", lava.sdk.api.MapPluginConfig())
            .getFeature(SearchableTracker::class)!!
            .search(SearchRequest(query = "sintel"), page = 0)

        // The late-set key MUST be on the wire (currentKey() read at build/get time).
        assertEquals(key, server.takeRequest().getHeader(authFieldName))
        assertEquals(1, result.items.size)
        assertEquals("Sintel", result.items.single().title)
    }
}
