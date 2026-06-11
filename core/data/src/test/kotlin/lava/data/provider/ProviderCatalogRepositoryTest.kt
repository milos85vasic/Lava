package lava.data.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Anti-Bluff (§6.J Second Law): the SUT [ProviderCatalogRepository] is a REAL
 * instance wired to a REAL [OkHttpClient] hitting a REAL [MockWebServer] socket
 * — nothing about the repository or the HTTP/parse path is mocked. The primary
 * assertions are on the PARSED, MAPPED descriptors a real user's provider list
 * renders from (ids / authType / capabilities), and on the Result outcome of
 * the error paths — not on call counts.
 */
class ProviderCatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ProviderCatalogRepository
    private lateinit var store: ProviderCatalogStore

    private val providersJson = """
        {
          "providers": [
            {
              "id": "rutracker",
              "displayName": "RuTracker.org",
              "kind": "native",
              "capabilities": ["SEARCH","BROWSE","TORRENT_DOWNLOAD","CAPTCHA_LOGIN"],
              "authType": "CAPTCHA_LOGIN",
              "encoding": "Windows-1251",
              "baseUrls": ["https://rutracker.org","https://rutracker.net"],
              "supportsAnonymous": false
            },
            {
              "id": "1337x",
              "displayName": "1337x",
              "kind": "jackett",
              "indexer": "1337x",
              "capabilities": ["SEARCH","MAGNET_LINK","TORRENT_DOWNLOAD"],
              "authType": "NONE",
              "encoding": "UTF-8",
              "baseUrls": [],
              "supportsAnonymous": true
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        store = InMemoryProviderCatalogStore()
        repository = ProviderCatalogRepository(
            httpClient = OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            store = store,
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    @Test
    fun fetchProvidersReturnsMappedDescriptors() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(providersJson),
        )

        val result = repository.fetchProviders(baseUrl())

        assertTrue("expected success, was: $result", result.isSuccess)
        val providers = result.getOrThrow()
        assertEquals(2, providers.size)

        val ids = providers.map { it.trackerId }
        assertTrue(ids.contains("rutracker"))
        assertTrue(ids.contains("1337x"))

        val jackett = providers.first { it.trackerId == "1337x" }
        assertEquals(AuthType.NONE, jackett.authType)
        assertTrue(jackett.capabilities.contains(TrackerCapability.SEARCH))
        assertTrue(jackett.capabilities.contains(TrackerCapability.MAGNET_LINK))
        assertTrue(jackett.supportsAnonymous)

        val native = providers.first { it.trackerId == "rutracker" }
        assertEquals(AuthType.CAPTCHA_LOGIN, native.authType)
        assertEquals("https://rutracker.org", native.baseUrls.first().url)
        assertTrue(native.baseUrls.first().isPrimary)
        assertFalse(native.supportsAnonymous)

        // The request hit the discovery route (user-observable on the wire).
        val recorded = server.takeRequest()
        assertEquals("/v1/providers", recorded.path)

        // Write-through cache survives for cold-start rendering.
        assertEquals(2, store.load(baseUrl()).size)
    }

    @Test
    fun serverErrorReturnsFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))

        val result = repository.fetchProviders(baseUrl())

        assertTrue("5xx must surface as Result.failure", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        // Cache untouched on failure.
        assertTrue(store.load(baseUrl()).isEmpty())
    }

    @Test
    fun timeoutReturnsFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = repository.fetchProviders(baseUrl())

        assertTrue("timeout must surface as Result.failure", result.isFailure)
    }

    @Test
    fun malformedBodyReturnsFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setBody("{ this is not valid json"))

        val result = repository.fetchProviders(baseUrl())

        assertTrue("parse error must surface as Result.failure", result.isFailure)
    }
}
