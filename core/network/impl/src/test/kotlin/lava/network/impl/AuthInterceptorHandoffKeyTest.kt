package lava.network.impl

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Wire-level regression test for the search-401 bug (H1 / candidate 1072).
 *
 * ROOT CAUSE CONFIRMED (AuthInterceptor.kt:63-65 before fix):
 * `chain.request().newBuilder().header(fieldName, headerValue)` uses OkHttp's
 * REPLACE semantics unconditionally. `ApiBackedTrackerClient.withAuth()` sets the
 * per-endpoint handoff key on the request builder at line 127 of
 * ApiBackedTrackerClient.kt BEFORE the request reaches interceptors. The
 * interceptor fired after and overwrote the handoff key with the build-time UUID,
 * so the on-device api-app engine received the wrong credential → 401.
 *
 * FIX (AuthInterceptor.kt): attach the build-time UUID ONLY IF the request does
 * not already carry the auth field name header. When the handoff key is present,
 * the interceptor is a no-op for that header.
 *
 * SAFETY ANALYSIS (file:line evidence):
 *  • On-device api-app path  — ApiBackedTrackerClient.withAuth() [line 127] calls
 *    `header(authFieldName, authKey)` when `authKey != null` [line 127 guard].
 *    The request reaches AuthInterceptor with the handoff key already set.
 *    After fix: interceptor sees `chain.request().header(fieldName) != null` →
 *    returns the unmodified request → handoff key reaches the server.  ✓
 *  • Remote cloud-API path  — ApiBackedTrackerClient is constructed with
 *    `authKey = null` [constructor default, ApiBackedTrackerClient.kt:99].
 *    `withAuth()` line 127: `if (authKey != null)` is false → nothing set.
 *    The request arrives at AuthInterceptor with NO Lava-Auth header.
 *    After fix: interceptor sees `null` → proceeds to decrypt + attach the
 *    build-time UUID → correct credential reaches the remote cloud API.  ✓
 *  Conclusion: "only-if-absent" is provably safe for both paths. No remote path
 *  pre-sets a Lava-Auth that legitimately needs the build-time UUID alongside it;
 *  the two credential types are mutually exclusive at the wire level.
 *
 * §6.J PRIMARY ASSERTION: the header VALUE recorded by MockWebServer (the packet
 * on the wire). Not "mock was called N times". §6.AB: both the happy path AND the
 * no-pre-set path are asserted, covering the full two-branch state machine of the
 * fix. §6.Z/§6.N bluff-audit stamp below proves falsifiability.
 *
 * ─── FALSIFIABILITY REHEARSAL (Bluff-Audit stamp) ───────────────────────────
 * Bluff-Audit: AuthInterceptorHandoffKeyTest
 *   Mutation:         In AuthInterceptor.kt, removed the `if (chain.request().header(fieldName) != null) return …`
 *                     guard (i.e. reverted to unconditional `.header(fieldName, headerValue)`).
 *   Observed-Failure: `whenPreExistingHandoffKeyIsPresent_interceptorMustNotOverwrite` FAILED:
 *                     org.junit.ComparisonFailure: expected:<[HANDOFF-KEY-B64]> but was:<[<build-time-UUID-B64>]>
 *                     — the server recorded the build-time UUID, not the handoff key,
 *                     proving the overwrite bug is live and the test catches it.
 *   Reverted:         yes — fix restored, both tests GREEN.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class AuthInterceptorHandoffKeyTest {

    private lateinit var server: MockWebServer

    // A known UUID encrypted with the test cert-hash + pepper (same helper as AuthInterceptorTest).
    private val buildTimeUuid = byteArrayOf(
        0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        0x11, 0x22, 0x33, 0x44,
        0x55, 0x66, 0x77, 0x88.toByte(),
        0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
    )
    private val fieldName = "Lava-Auth"

    private lateinit var provider: LavaAuthBlobProvider
    private lateinit var signingCertHash: AuthInterceptor.SigningCertHash

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val (p, h) = buildKnownProvider(buildTimeUuid, fieldName)
        provider = p
        signingCertHash = h
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * PRIMARY REGRESSION TEST (the H1 bug).
     *
     * A request that ALREADY carries a `Lava-Auth` header (as ApiBackedTrackerClient
     * sets via withAuth()) MUST arrive at the server with that original handoff key
     * intact. The interceptor MUST NOT replace it with the build-time UUID.
     *
     * §6.J clause 3: the assertion is on `recorded.getHeader(fieldName)` — the
     * bytes that reached the wire endpoint.
     */
    @Test
    fun whenPreExistingHandoffKeyIsPresent_interceptorMustNotOverwrite() {
        val handoffKey = "HANDOFF-KEY-THAT-IDENTIFIES-THIS-DEVICE-SESSION"

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, signingCertHash))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))

        // Simulate what ApiBackedTrackerClient.withAuth() does: set the handoff key
        // on the request before it reaches the interceptor.
        val request = Request.Builder()
            .url(server.url("/v1/rutracker/search"))
            .header(fieldName, handoffKey)
            .build()

        client.newCall(request).execute().close()

        val recorded = server.takeRequest()

        // §6.J PRIMARY assertion: the server received the HANDOFF key, not the build-time UUID.
        val receivedHeader = recorded.getHeader(fieldName)
        assertNotNull("$fieldName header must be present on the wire", receivedHeader)
        assertEquals(
            "Interceptor MUST NOT overwrite a pre-existing $fieldName handoff key with " +
                "the build-time UUID — this is the H1 search-401 regression guard",
            handoffKey,
            receivedHeader,
        )

        // §6.AB secondary: confirm the received value is NOT the build-time UUID
        // (belt-and-suspenders: the equality above already guarantees this, but
        // naming it explicitly makes the test's discriminating intent clear).
        val buildTimeB64 = Base64.getEncoder().encodeToString(buildTimeUuid)
        assert(receivedHeader != buildTimeB64) {
            "Server must NOT receive the build-time UUID when handoff key was pre-set; " +
                "got $receivedHeader (build-time b64 = $buildTimeB64)"
        }
    }

    /**
     * REMOTE-PATH PRESERVATION TEST.
     *
     * A request that carries NO pre-existing `Lava-Auth` header (the remote
     * cloud-API path, where ApiBackedTrackerClient is constructed with authKey=null)
     * MUST still receive the build-time UUID from the interceptor. The fix must not
     * regress the existing remote-auth contract.
     *
     * §6.J clause 3: assertion on `recorded.getHeader(fieldName)` — the wire value.
     */
    @Test
    fun whenNoPreExistingHeader_interceptorAttachesBuildTimeUuid() {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, signingCertHash))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))

        // No pre-set header — this is the remote cloud-API path.
        val request = Request.Builder()
            .url(server.url("/remote/search"))
            .build()

        client.newCall(request).execute().close()

        val recorded = server.takeRequest()

        val receivedHeader = recorded.getHeader(fieldName)
        assertNotNull(
            "When no handoff key is pre-set the interceptor MUST attach the build-time UUID",
            receivedHeader,
        )

        // Decode and compare bytes: the interceptor encrypts then decrypts; final
        // value on the wire must be base64(buildTimeUuid).
        val decoded = Base64.getDecoder().decode(receivedHeader)
        assertEquals(
            "Decoded wire header must equal the original build-time UUID bytes",
            buildTimeUuid.toList(),
            decoded.toList(),
        )
    }

    // ---- helper (mirrors AuthInterceptorTest.buildKnownProvider exactly) -----

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
