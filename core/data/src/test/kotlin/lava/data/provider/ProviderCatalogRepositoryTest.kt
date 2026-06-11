package lava.data.provider

import kotlinx.coroutines.runBlocking
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Defect-A regression suite (2026-06-12) — "only 4 providers in onboarding".
 *
 * **The bluff this test replaces.** The prior version started MockWebServer on
 * **plain HTTP** and constructed the SUT with a vanilla [OkHttpClient]. It
 * passed green for weeks while every real-device fetch failed, because the real
 * on-device api-app serves `/providers` over a **self-signed LAN cert** behind
 * the `Lava-Auth` header gate — a boundary the plain-HTTP test never crossed
 * (Sixth Law clause 1 violation). Crashlytics `042b9b61`
 * (`provider_catalog_fetch_failed` → `CertPathValidatorException`, 1.3.3-1060,
 * Galaxy S23 Ultra) is the real-device proof.
 *
 * **What this suite now guarantees (§6.J Second + Sixth Law).** The SUT is a
 * REAL [ProviderCatalogRepository] hitting a REAL self-signed-HTTPS
 * [MockWebServer] socket whose dispatcher REQUIRES the `Lava-Auth` header — the
 * exact TLS + auth boundary the user's onboarding fetch crosses. Primary
 * assertions are on the parsed/mapped descriptors the provider list renders and
 * on the on-the-wire auth header.
 *
 * **Built-in falsifiability (Sixth Law clause 2).** [strictClientFailsTheHandshake]
 * IS the discrimination test: it drives the same self-signed server with a
 * STRICT (system-trust-store) client and asserts the fetch FAILS. That is the
 * pre-fix behaviour — if the production wiring regressed back to the unqualified
 * strict client, [fetchOverSelfSignedTlsWithAuthSucceeds] could not pass.
 *
 * Bluff-Audit:
 *   Mutation: route fetchProviders() back through a strict OkHttpClient (the
 *             pre-fix wiring) — i.e. pass `strictClient` as the SUT's lanHttpClient.
 *   Observed: fetchOverSelfSignedTlsWithAuthSucceeds → "expected success, was:
 *             Failure(javax.net.ssl.SSLHandshakeException: ... Trust anchor for
 *             certification path not found)". strictClientFailsTheHandshake is
 *             the codified form of that mutation and passes unmutated.
 *   Reverted: yes — production routes through the @Named("lan") permissive client.
 */
class ProviderCatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var store: ProviderCatalogStore

    /** Mirrors NetworkModule.lanOkHttpClient: trust-any-cert, verify-any-host. */
    private lateinit var permissiveClient: OkHttpClient

    /** Mirrors NetworkModule.okHttpClient: system trust store, strict TLS. */
    private lateinit var strictClient: OkHttpClient

    private val authKey = "test-instance-key"
    private val authField = "Lava-Auth"

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
        // Self-signed cert for localhost/127.0.0.1 — exactly the on-device
        // api-app's situation: a cert the system trust store does NOT chain to.
        val serverCert = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        server = MockWebServer()
        server.useHttps(serverHandshake.sslSocketFactory(), false)
        server.start()

        store = InMemoryProviderCatalogStore()
        permissiveClient = buildPermissiveClient()
        strictClient = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun repository(client: OkHttpClient = permissiveClient): ProviderCatalogRepository =
        ProviderCatalogRepository(
            lanHttpClient = client,
            authFieldName = authField,
            store = store,
        )

    private fun baseUrl(): String = server.url("/").toString()

    /** Returns 200 + body only when the request carries the right Lava-Auth key. */
    private fun enqueueAuthGatedCatalogue() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authField) == authKey) {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(providersJson)
                } else {
                    MockResponse().setResponseCode(401).setBody("missing/invalid auth")
                }
        }
    }

    // CHALLENGE — the fix proof: real self-signed TLS + real auth header.
    @Test
    fun fetchOverSelfSignedTlsWithAuthSucceeds() = runBlocking {
        enqueueAuthGatedCatalogue()

        val result = repository().fetchProviders(baseUrl(), authKey)

        assertTrue("expected success, was: $result", result.isSuccess)
        val providers = result.getOrThrow()
        assertEquals(2, providers.size)

        // The user-visible payoff: a provider the bundled set does NOT have —
        // the jackett indexer — is present. This is the whole "more than 4" point.
        val jackett = providers.first { it.trackerId == "1337x" }
        assertEquals(AuthType.NONE, jackett.authType)
        assertTrue(jackett.capabilities.contains(TrackerCapability.SEARCH))
        assertTrue(jackett.capabilities.contains(TrackerCapability.MAGNET_LINK))
        assertTrue(jackett.supportsAnonymous)

        val native = providers.first { it.trackerId == "rutracker" }
        assertEquals(AuthType.CAPTCHA_LOGIN, native.authType)
        assertEquals("https://rutracker.org", native.baseUrls.first().url)
        assertFalse(native.supportsAnonymous)

        // The auth header crossed the wire (api-app gate would have 401'd otherwise).
        val recorded = server.takeRequest()
        assertEquals("/providers", recorded.path)
        assertEquals(authKey, recorded.getHeader(authField))

        // Write-through cache survives for cold-start rendering.
        assertEquals(2, store.load(baseUrl()).size)
    }

    // CHALLENGE — discrimination: the pre-fix strict client CANNOT clear the
    // self-signed handshake, so the fetch fails. Proves the TLS boundary is real.
    @Test
    fun strictClientFailsTheHandshake() = runBlocking {
        enqueueAuthGatedCatalogue()

        val result = repository(client = strictClient).fetchProviders(baseUrl(), authKey)

        assertTrue("strict TLS must reject the self-signed cert: $result", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        // Cache untouched on failure → onboarding falls back to bundled providers.
        assertTrue(store.load(baseUrl()).isEmpty())
    }

    // CHALLENGE — the auth header is load-bearing: omit the key → api-app 401 → failure.
    @Test
    fun missingAuthKeyIsRejectedByTheGate() = runBlocking {
        enqueueAuthGatedCatalogue()

        val result = repository().fetchProviders(baseUrl(), authKey = null)

        assertTrue("401 from the auth gate must surface as failure: $result", result.isFailure)
        assertTrue(store.load(baseUrl()).isEmpty())
    }

    @Test
    fun serverErrorReturnsFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))

        val result = repository().fetchProviders(baseUrl(), authKey)

        assertTrue("5xx must surface as Result.failure", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(store.load(baseUrl()).isEmpty())
    }

    @Test
    fun timeoutReturnsFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = repository().fetchProviders(baseUrl(), authKey)

        assertTrue("timeout must surface as Result.failure", result.isFailure)
    }

    @Test
    fun malformedBodyReturnsFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setBody("{ this is not valid json"))

        val result = repository().fetchProviders(baseUrl(), authKey)

        assertTrue("parse error must surface as Result.failure", result.isFailure)
    }

    private fun buildPermissiveClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
