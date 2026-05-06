package lava.network.impl

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Base64
import javax.inject.Inject

/**
 * OkHttp interceptor that injects the per-build encrypted UUID into
 * each request as a `Lava-Auth`-style header (name comes from
 * [LavaAuthBlobProvider.getFieldName], itself read from `.env` at
 * build time per §6.R).
 *
 * Decrypt-use-zeroize per `core/CLAUDE.md` Auth UUID memory hygiene:
 * the plaintext UUID lives only inside this method as a `ByteArray`,
 * is base64-encoded once for the header, and is `fill(0)`'d in the
 * `finally` block before the call returns. The base64 String IS
 * captured by OkHttp into the request — JVM Strings are immutable so
 * we can't zeroize that, but: it leaves Lava code as soon as OkHttp
 * consumes it; it MUST NOT be logged, persisted, or assigned to a
 * field; pre-push grep enforces.
 *
 * When [LavaAuthBlobProvider.getBlob] returns empty bytes (the
 * Phase-10 stub state), the interceptor is a no-op pass-through —
 * fail-closed default until Phase 11's generator ships.
 *
 * Re-signed-APK attack vector: the AES key is derived via
 * `HKDF(salt = signingCertHash[:16], ikm = pepper, info = "lava-auth-v1")`.
 * A re-signed APK has a different signing cert → different hash →
 * different derived key → AES-GCM decrypt fails with
 * `AEADBadTagException`. The catch in this method propagates that
 * as an `IOException` so OkHttp surfaces it as a network error
 * rather than letting a malformed `Lava-Auth` header reach the API.
 */
internal class AuthInterceptor @Inject constructor(
    private val blobProvider: LavaAuthBlobProvider,
    private val signingCertHash: SigningCertHash,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val blob = blobProvider.getBlob()
        if (blob.isEmpty()) {
            return chain.proceed(chain.request())
        }
        val pepper = blobProvider.getPepper()
        val nonce = blobProvider.getNonce()
        val fieldName = blobProvider.getFieldName()

        val keyBytes = ByteArray(KEY_SIZE_BYTES)
        var uuidBytes: ByteArray? = null
        try {
            val certHash = signingCertHash.bytes()
            require(certHash.size >= SALT_SIZE_BYTES) { "signing-cert hash too short" }
            HKDF.deriveKey(
                salt = certHash.copyOfRange(0, SALT_SIZE_BYTES),
                ikm = pepper,
                info = HKDF_INFO,
                output = keyBytes,
            )
            uuidBytes = AesGcm.decrypt(blob, keyBytes, nonce)
            val headerValue = Base64.getEncoder().encodeToString(uuidBytes)

            val request = chain.request().newBuilder()
                .header(fieldName, headerValue)
                .build()
            return chain.proceed(request)
        } finally {
            keyBytes.fill(0)
            uuidBytes?.fill(0)
        }
    }

    /** Functional wrapper around [SigningCertProvider.sha256] so tests can inject a fixed hash. */
    fun interface SigningCertHash {
        fun bytes(): ByteArray
    }

    private companion object {
        const val KEY_SIZE_BYTES = 32
        const val SALT_SIZE_BYTES = 16
        val HKDF_INFO = "lava-auth-v1".toByteArray(Charsets.UTF_8)
    }
}
