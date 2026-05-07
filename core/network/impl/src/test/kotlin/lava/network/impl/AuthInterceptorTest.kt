package lava.network.impl

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * §6.J primary-on-user-visible-state: the assertion is on the
     * BYTES THAT REACHED THE API (mockServer received the header,
     * its base64 decodes to the original UUID). Not "interceptor was
     * called N times".
     */
    @Test
    fun `intercept adds Lava-Auth header carrying base64 of decrypted UUID`() {
        val uuid = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        )
        val (provider, signingCertHash) = buildKnownProvider(uuid, "X-Test-Auth")

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, signingCertHash))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        val recorded = server.takeRequest()
        val headerValue = recorded.getHeader("X-Test-Auth")
        assertNotNull("X-Test-Auth header missing on outgoing request", headerValue)
        val decoded = Base64.getDecoder().decode(headerValue)
        assertEquals(
            "decrypted+base64-decoded UUID must match original",
            uuid.toList(),
            decoded.toList(),
        )
    }

    @Test
    fun `intercept skips header when blob is empty (stub-provider state)`() {
        val provider = object : LavaAuthBlobProvider {
            override fun getBlob(): ByteArray = ByteArray(0)
            override fun getNonce(): ByteArray = ByteArray(0)
            override fun getPepper(): ByteArray = ByteArray(0)
            override fun getFieldName(): String = ""
        }
        val signingCertHash = AuthInterceptor.SigningCertHash { ByteArray(32) { 0x42 } }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, signingCertHash))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        val recorded = server.takeRequest()
        assertNull("no auth header expected when stub provider is in place", recorded.getHeader("X-Test-Auth"))
        assertNull(recorded.getHeader("Lava-Auth"))
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun `intercept fails closed when signing cert hash diverges (re-signed APK simulation)`() {
        val uuid = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        )
        val (provider, _) = buildKnownProvider(uuid, "X-Test-Auth")
        // The provider was built with cert-hash filled with 0x42; switch to 0x43.
        val divergedCertHash = AuthInterceptor.SigningCertHash {
            ByteArray(32) { 0x43 }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, divergedCertHash))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        } catch (e: java.io.IOException) {
            // OkHttp wraps interceptor exceptions in IOException; unwrap to surface AEADBadTagException
            val cause = e.cause
            if (cause is javax.crypto.AEADBadTagException) throw cause
            throw e
        }
    }

    /**
     * Build a [LavaAuthBlobProvider] that encrypts the given plaintext
     * UUID with a key derived from a known cert-hash + pepper + the
     * production HKDF-SHA256 chain, then returns the resulting blob +
     * nonce. The returned [AuthInterceptor.SigningCertHash] resolves
     * to the same cert-hash so AuthInterceptor's runtime decrypt
     * succeeds — exactly the contract a Phase-11 generator provides.
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
        val signingCertHash = AuthInterceptor.SigningCertHash { certHashFull }
        return provider to signingCertHash
    }
}
