package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.sdk.api.MapPluginConfig
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.registry.PluginFactory
import lava.tracker.api.AuthType
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.registry.DefaultTrackerRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KClass

/**
 * End-to-end real-stack test (plan Task 4.2): the REAL [DefaultTrackerRegistry]
 * populated with REAL [ApiBackedTrackerClient]s, resolved + driven against a
 * [MockWebServer]. This is the load-bearing acceptance gate (§6.J clause 4) that
 * `populateFrom` → `get(id)` → `search()` actually reaches the API over the wire.
 *
 * It lives in `:core:tracker:client` (not `:core:tracker:registry`) because the
 * concrete `ApiBackedTrackerClient` is only visible here.
 *
 * FALSIFIABILITY REHEARSAL: forcing [ApiBackedTrackerClient]'s search path
 * template to "/v1/$trackerId/find" makes
 * [populatedClient_resolvesAndSearchesOverWire] FAIL on the recorded-path
 * assertion ("expected:</v1/jackett-1337x/search?...> but was:<.../find?...>").
 */
class DynamicRegistryRealClientTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ApiBaseUrlHolder.set(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiBaseUrlHolder.reset()
    }

    private fun remote(id: String, vararg caps: TrackerCapability) = RemoteTrackerDescriptor(
        trackerId = id,
        displayName = id,
        baseUrls = listOf(MirrorUrl(url = "https://$id.example", isPrimary = true)),
        capabilities = caps.toSet(),
        authType = AuthType.NONE,
        encoding = "UTF-8",
    )

    private fun registryWithRealBuilder(): DefaultTrackerRegistry =
        DefaultTrackerRegistry().apply {
            setApiClientFactory { descriptor ->
                ApiBackedTrackerClient(
                    descriptor = descriptor,
                    apiBaseUrl = ApiBaseUrlHolder.current(),
                    httpClient = httpClient,
                    authFieldName = "Lava-Auth",
                )
            }
        }

    @Test
    fun populateFrom_listAvailableTrackers_returnsApiCatalogue() {
        val registry = registryWithRealBuilder()
        registry.populateFrom(
            listOf(
                remote("jackett-1337x", TrackerCapability.SEARCH),
                remote("jackett-yts", TrackerCapability.SEARCH),
            ),
        )
        assertEquals(
            listOf("jackett-1337x", "jackett-yts"),
            registry.list().map { it.id }.sorted(),
        )
    }

    @Test
    fun populatedClient_resolvesAndSearchesOverWire() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "provider": "jackett-1337x",
                  "page": 0,
                  "totalPages": 1,
                  "results": [
                    { "id": "tt99", "title": "Big Buck Bunny", "seeders": 7,
                      "magnetLink": "magnet:?xt=urn:btih:BBB" }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val registry = registryWithRealBuilder()
        registry.populateFrom(listOf(remote("jackett-1337x", TrackerCapability.SEARCH)))

        // Resolve the live ApiBackedTrackerClient from the registry and search.
        val client = registry.get("jackett-1337x", MapPluginConfig())
        assertTrue("resolved client is the API-backed one", client is ApiBackedTrackerClient)
        val searchable = client.getFeature(SearchableTracker::class)!!
        val result = searchable.search(SearchRequest(query = "bunny"), page = 0)

        val recorded = server.takeRequest()
        assertEquals("/v1/jackett-1337x/search?query=bunny&page=0&sort=date&order=descending", recorded.path)
        assertEquals(1, result.items.size)
        assertEquals("Big Buck Bunny", result.items.single().title)
        assertEquals("jackett-1337x", result.items.single().trackerId)
    }

    @Test
    fun emptyCatalogue_restoresBundledFallback_notBlank() {
        val registry = registryWithRealBuilder()
        // Register a single bundled provider as the fallback.
        val bundledDescriptor = remote("rutracker", TrackerCapability.SEARCH)
        registry.register(
            object : PluginFactory<TrackerDescriptor, TrackerClient> {
                override val descriptor: TrackerDescriptor = bundledDescriptor
                override fun create(config: PluginConfig): TrackerClient =
                    object : TrackerClient {
                        override val descriptor: TrackerDescriptor = bundledDescriptor
                        override suspend fun healthCheck() = true
                        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = null
                        override fun close() {}
                    }
            },
        )
        registry.populateFrom(listOf(remote("jackett-1337x", TrackerCapability.SEARCH)))
        assertEquals(listOf("jackett-1337x"), registry.list().map { it.id })

        registry.populateFrom(emptyList())
        assertEquals(
            "empty catalogue restores the bundled provider (never blank)",
            listOf("rutracker"),
            registry.list().map { it.id },
        )
    }
}
