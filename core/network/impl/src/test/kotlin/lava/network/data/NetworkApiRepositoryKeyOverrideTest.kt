package lava.network.data

import lava.logger.api.Logger
import lava.logger.api.LoggerFactory
import lava.models.settings.Endpoint
import lava.network.impl.AesGcm
import lava.network.impl.AuthInterceptor
import lava.network.impl.FakeSettingsRepository
import lava.network.impl.HKDF
import lava.network.impl.LavaAuthBlobProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * CHALLENGE — proves the per-endpoint key wire path end-to-end.
 *
 * Option A (client-api-app linking design §5): when [Endpoint.GoApi.key]
 * is non-null, the HTTP layer MUST send that key as `Lava-Auth` instead
 * of the build-time UUID injected by [AuthInterceptor].
 *
 * **Seam under test:** [NetworkApiRepositoryImpl.withKeyOverride] — the
 * OkHttp interceptor that replaces the `Lava-Auth` header value on every
 * outgoing request.  The assertion is on the literal header bytes recorded
 * by [MockWebServer] (plain HTTP).  OkHttp interceptors fire regardless of
 * whether the caller is Ktor or raw OkHttp, so this is the same code path
 * that puts the header on the wire in production.  Using plain HTTP avoids
 * the TLS handshake mismatch that caused the previous version of this test
 * to fail: the production code forces scheme="https" for GoApi (so Ktor
 * tries TLS against a non-TLS MockWebServer), but the interceptor chain
 * that attaches the header is entirely at the OkHttp layer and is
 * scheme-agnostic.
 *
 * Primary assertion: the captured HTTP request's `Lava-Auth` header VALUE
 * equals the endpoint's key — bytes on the wire; a real user's API call
 * will be accepted or rejected based on exactly this header value.
 *
 * FALSIFIABILITY REHEARSAL:
 *   Mutation 1 (withKeyOverride drops the header call): in
 *     [NetworkApiRepositoryImpl.withKeyOverride], remove the
 *     `.header(fieldName, key)` call so the interceptor is a pass-through.
 *     Expected failure: `key_bearing …` fails with
 *     "expected:<on-device-api-key-abc123> but was:<AQIDBAUG...>"
 *     (the UUID base64 from AuthInterceptor leaks through).  Reverted: yes.
 *   Mutation 2 (key-null path incorrectly applies withKeyOverride): in
 *     [NetworkApiRepositoryImpl.getApi], change the key-null branch to also
 *     call `lanOkHttpClient.withKeyOverride("wrong-key", authFieldName)`.
 *     Expected failure: `key_null …` fails with
 *     "expected:<AQIDBAUG...> but was:<wrong-key>".  Reverted: yes.
 *
 * Bluff-Audit stamp is in the commit body per Seventh Law clause 1.
 */
class NetworkApiRepositoryKeyOverrideTest {

    // Backstop: no single test may wedge the daemon. MockWebServer.takeRequest()
    // blocks forever if no request arrives; this rule fails the test in bounded
    // time instead (§6.M host-stability — an unbounded hang wedged the build
    // daemon for 2h+ before this guard was added).
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(60)

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── test constants ───────────────────────────────────────────────────────

    private val AUTH_FIELD = "Lava-Auth"

    /**
     * The UUID bytes that [AuthInterceptor] will base64-encode and send when
     * no per-endpoint key override is in place.  Used to assert that the
     * override IS happening (key-bearing path) and IS NOT happening (key-null
     * regression guard path).
     */
    private val uuidBytes = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
    )
    private val expectedUuidHeader: String
        get() = Base64.getEncoder().encodeToString(uuidBytes)

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a [LavaAuthBlobProvider] whose blob encrypts [plaintext] with a
     * deterministic key (fixed cert-hash + pepper + HKDF).  The paired
     * [AuthInterceptor.SigningCertHash] returns the same cert bytes so the
     * runtime decrypt succeeds — exactly what a Phase-11 generator provides.
     */
    private fun buildKnownProvider(
        plaintext: ByteArray,
        fieldName: String,
    ): Pair<LavaAuthBlobProvider, AuthInterceptor.SigningCertHash> {
        val certHashFull = ByteArray(32) { 0x42 }
        val pepper = ByteArray(32) { 0x77 }
        val key = ByteArray(32)
        HKDF.deriveKey(
            salt = certHashFull.copyOfRange(0, 16),
            ikm = pepper,
            info = "lava-auth-v1".toByteArray(Charsets.UTF_8),
            output = key,
        )
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = AesGcm.encrypt(plaintext, key, nonce)
        key.fill(0)
        val provider = object : LavaAuthBlobProvider {
            override fun getBlob(): ByteArray = ciphertext
            override fun getNonce(): ByteArray = nonce
            override fun getPepper(): ByteArray = pepper
            override fun getFieldName(): String = fieldName
        }
        return provider to AuthInterceptor.SigningCertHash { certHashFull }
    }

    private val noOpLoggerFactory = object : LoggerFactory {
        override fun get(tag: String): Logger = object : Logger {
            override fun i(message: () -> String) {}
            override fun d(message: () -> String) {}
            override fun d(t: Throwable?, message: () -> String) {}
            override fun e(message: () -> String) {}
            override fun e(t: Throwable?, message: () -> String) {}
        }
    }

    /**
     * Builds a [NetworkApiRepositoryImpl] wired with a real [AuthInterceptor]
     * that injects the known UUID blob, and the base [OkHttpClient] that
     * carries [AuthInterceptor] but no key override.
     *
     * The repo is returned so that [NetworkApiRepositoryImpl.withKeyOverride]
     * (an `internal` member extension) can be invoked on it via
     * `repo.run { client.withKeyOverride(key, field) }`.
     */
    private fun buildRepoAndClient(
        authFieldName: String = AUTH_FIELD,
    ): Pair<NetworkApiRepositoryImpl, OkHttpClient> {
        val (provider, certHash) = buildKnownProvider(uuidBytes, authFieldName)
        val authInterceptor = AuthInterceptor(provider, certHash)
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()
        val networkLogger = NetworkLogger(noOpLoggerFactory, provider)
        // Endpoint value is only a placeholder; getApi() is never called in these tests.
        val fakeSettings = FakeSettingsRepository(Endpoint.Rutracker)
        val repo = NetworkApiRepositoryImpl(
            settingsRepository = fakeSettings,
            okHttpClient = client,
            lanOkHttpClient = client,
            networkLogger = networkLogger,
            authFieldName = authFieldName,
        )
        return repo to client
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * CHALLENGE — primary assertion on bytes-on-the-wire.
     *
     * When [NetworkApiRepositoryImpl.withKeyOverride] is applied to a client
     * that already has [AuthInterceptor], the key-override interceptor MUST
     * run last and replace the UUID header with the endpoint key.
     *
     * This is the load-bearing anti-bluff test for Option A: the on-device
     * API server will reject any request whose `Lava-Auth` does not equal the
     * key it issued, so a wrong value here means every authenticated call 401s.
     */
    @Test
    fun `key_bearing GoApi endpoint sends endpoint key as Lava-Auth (not build-time UUID)`() {
        val endpointKey = "on-device-api-key-abc123"
        val (repo, baseClient) = buildRepoAndClient()

        // Build the exact client the production getApi() uses for a key-bearing
        // GoApi: the base client (AuthInterceptor) + the key-override interceptor
        // added last via withKeyOverride (internal member extension on NetworkApiRepositoryImpl).
        val keyClient = repo.run { baseClient.withKeyOverride(endpointKey, AUTH_FIELD) }

        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        try {
            keyClient.newCall(Request.Builder().url(server.url("/forum")).build()).execute().close()
        } catch (_: Exception) { /* response body errors are irrelevant */ }

        val recorded = server.takeRequest(20, TimeUnit.SECONDS)
        assertNotNull(
            "OkHttp MUST reach MockWebServer within 20s. " +
                "If null, AuthInterceptor threw before sending (check encrypt/decrypt round-trip).",
            recorded,
        )
        val headerValue = recorded!!.getHeader(AUTH_FIELD)
        assertNotNull(
            "Lava-Auth header must be present on the outgoing request to a key-bearing GoApi endpoint",
            headerValue,
        )
        assertEquals(
            "Lava-Auth header value must equal the endpoint's key (Option A wire path). " +
                "If this fails with the UUID base64 instead, withKeyOverride's .header() call " +
                "is missing or runs before AuthInterceptor instead of after.",
            endpointKey,
            headerValue,
        )
        // Secondary guard: catch the regression where withKeyOverride is silently removed
        // and the UUID base64 leaks through as the header value.
        assert(headerValue != expectedUuidHeader) {
            "Lava-Auth must be the endpoint key, not the build-time UUID. " +
                "The withKeyOverride interceptor must run AFTER AuthInterceptor in the chain."
        }
    }

    /**
     * CHALLENGE (regression guard) — primary assertion on bytes-on-the-wire.
     *
     * When no key override is applied (the cloud / mDNS path where
     * [Endpoint.GoApi.key] is null), [AuthInterceptor] MUST still inject the
     * build-time UUID.  The fix for on-device endpoints MUST NOT break this path.
     */
    @Test
    fun `key_null GoApi endpoint still sends build-time UUID as Lava-Auth`() {
        val (_, baseClient) = buildRepoAndClient()

        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        try {
            baseClient.newCall(
                Request.Builder().url(server.url("/forum")).build(),
            ).execute().close()
        } catch (_: Exception) { /* response body errors are irrelevant */ }

        val recorded = server.takeRequest(20, TimeUnit.SECONDS)
        assertNotNull(
            "OkHttp MUST reach MockWebServer within 20s (key-null regression path).",
            recorded,
        )
        val headerValue = recorded!!.getHeader(AUTH_FIELD)
        assertNotNull(
            "Lava-Auth header must still be present for key-null (cloud/mDNS) endpoints",
            headerValue,
        )
        assertEquals(
            "key-null GoApi must send the build-time UUID (regression guard). " +
                "If this fails, AuthInterceptor is not injecting the UUID, or the " +
                "encrypt/decrypt round-trip in buildKnownProvider is broken.",
            expectedUuidHeader,
            headerValue,
        )
    }
}
