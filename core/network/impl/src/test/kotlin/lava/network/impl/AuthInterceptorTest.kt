package lava.network.impl

import lava.logger.api.Logger
import lava.logger.api.LoggerFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * REPRODUCE-FIRST fail-open regression (§6.AK / §6.J, 2026-07-02).
     *
     * When the running APK's signing cert does not match the cert the auth blob
     * was generated against — an androidTest build, a re-signed APK, or any
     * cert/blob drift — the HKDF-derived key is wrong and `AesGcm.decrypt`
     * THROWS `AEADBadTagException`. Before the fix that exception propagated out
     * of `intercept()` → OkHttp surfaced it as an `IOException` → EVERY request
     * on the `@Named("lan")` client failed, INCLUDING the public `/providers`
     * catalogue fetch that needs no auth (device-observed goapi-onboarding
     * fallback root cause). A decrypt failure must NOT crash every request.
     *
     * The interceptor MUST fail OPEN: proceed WITHOUT the Lava-Auth header.
     * Public endpoints do not need it; auth-gated endpoints already handle 401.
     *
     * §6.J PRIMARY assertion (packet on the wire): the MockWebServer RECEIVED
     * the request at `/providers` and it carried NO auth header. Not
     * "an exception did/didn't fire".
     *
     * §6.AC/§6.H secondary: the fail-open path records a non-fatal whose text is
     * the error CLASS ONLY — and NEVER the blob, derived key, nonce, or a header
     * value (asserted below).
     *
     * RED (pre-fix): `execute()` throws `IOException` (wrapping
     * `AEADBadTagException`) before the server ever receives the request →
     * `takeRequest()` is never reached, the test fails at `execute()`.
     * GREEN (post-fix): the server receives `/providers` with no auth header.
     *
     * ─── FALSIFIABILITY REHEARSAL (Bluff-Audit stamp) ───────────────────────
     * Bluff-Audit: AuthInterceptorTest."intercept fails OPEN when signing cert hash diverges (cert-blob drift simulation)"
     *   Mutation:         In AuthInterceptor.intercept, deleted the `catch (e: Exception)`
     *                     fail-open branch (leaving try/finally with no catch).
     *   Observed-Failure: javax.crypto.AEADBadTagException: Tag mismatch
     *                     (propagated as java.io.IOException out of execute();
     *                     the server never received the request).
     *   Reverted:         yes — catch restored, test GREEN.
     * ─────────────────────────────────────────────────────────────────────────
     */
    @Test
    fun `intercept fails OPEN when signing cert hash diverges (cert-blob drift simulation)`() {
        val uuid = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        )
        val (provider, _) = buildKnownProvider(uuid, "X-Test-Auth")
        // The provider was built with cert-hash filled with 0x42; switch to 0x43,
        // so the HKDF-derived key is wrong and AES-GCM decrypt fails.
        val divergedCertHash = AuthInterceptor.SigningCertHash {
            ByteArray(32) { 0x43 }
        }
        val capturingLogs = CapturingLoggerFactory()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, divergedCertHash, capturingLogs))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))

        // MUST NOT throw — fail-open. (Pre-fix this line throws IOException.)
        client.newCall(Request.Builder().url(server.url("/providers")).build()).execute().close()

        // §6.J PRIMARY: the request reached the server (fail-open let it through)
        // and carried NO auth header (the malformed one was NOT attached).
        val recorded = server.takeRequest()
        assertEquals("/providers", recorded.path)
        assertNull(
            "decrypt failure must NOT attach a (malformed) auth header",
            recorded.getHeader("X-Test-Auth"),
        )
        assertNull(recorded.getHeader("Lava-Auth"))

        // §6.AC: a non-fatal was recorded naming the error CLASS.
        val logLine = capturingLogs.lines.singleOrNull { it.contains("decrypt failed") }
        assertNotNull("fail-open path must record a §6.AC non-fatal", logLine)
        assertTrue(
            "non-fatal must name the error class",
            logLine!!.contains("AEADBadTagException"),
        )
        // §6.H: the non-fatal MUST NOT leak the blob / nonce / pepper / a header value.
        val blobB64 = Base64.getEncoder().encodeToString(provider.getBlob())
        val nonceB64 = Base64.getEncoder().encodeToString(provider.getNonce())
        assertFalse("non-fatal leaked the blob (§6.H)", logLine.contains(blobB64))
        assertFalse("non-fatal leaked the nonce (§6.H)", logLine.contains(nonceB64))
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

    /** Captures every logged line so the §6.AC non-fatal recording is provable (not a bluff). */
    private class CapturingLoggerFactory : LoggerFactory {
        val lines = mutableListOf<String>()

        override fun get(tag: String): Logger = object : Logger {
            override fun i(message: () -> String) { lines += message() }
            override fun d(message: () -> String) { lines += message() }
            override fun d(t: Throwable?, message: () -> String) { lines += message() }
            override fun e(message: () -> String) { lines += message() }
            override fun e(t: Throwable?, message: () -> String) { lines += message() }
        }
    }
}
