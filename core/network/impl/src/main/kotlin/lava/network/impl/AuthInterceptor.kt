package lava.network.impl

import lava.logger.api.LoggerFactory
import okhttp3.Interceptor
import okhttp3.Request
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
 * Phase-10 stub state), the interceptor is a no-op pass-through until
 * Phase 11's generator ships.
 *
 * Re-signed-APK / cert-blob-drift vector — FAIL OPEN (2026-07-02):
 * the AES key is derived via
 * `HKDF(salt = signingCertHash[:16], ikm = pepper, info = "lava-auth-v1")`.
 * A re-signed APK, an androidTest build, or any cert/blob drift has a
 * different signing cert → different hash → different derived key →
 * AES-GCM decrypt fails with `AEADBadTagException`. Previously that
 * exception propagated out of [intercept] and OkHttp surfaced it as an
 * `IOException` — which killed EVERY request on the `@Named("lan")`
 * client, including the PUBLIC `/providers` catalogue fetch that needs
 * no auth at all (device-observed goapi-onboarding fallback root cause,
 * "LAYER 1 candidate 1"). A decrypt failure MUST NOT crash every
 * authenticated request. So the decrypt + header-attach is wrapped in a
 * try/catch: on ANY failure we record a §6.AC non-fatal (error CLASS
 * only — never the blob/key/nonce/header value, per §6.H) and proceed
 * WITHOUT the `Lava-Auth` header. Public endpoints do not need it;
 * auth-gated endpoints already handle the resulting 401. The final
 * `chain.proceed(request)` lives OUTSIDE the catch so genuine network
 * errors from the call are never swallowed.
 */
internal class AuthInterceptor @Inject constructor(
    private val blobProvider: LavaAuthBlobProvider,
    private val signingCertHash: SigningCertHash,
    // Optional so existing manual construction (tests) stays additive; the
    // Hilt module wires the real module logger. Nullable → no-op when absent.
    private val loggerFactory: LoggerFactory? = null,
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
        // Build the request to send. Everything that can fail (key derivation +
        // AES-GCM decrypt) is inside this try; the actual `chain.proceed` is
        // outside it so a network IOException is NOT mistaken for a decrypt
        // failure and never triggers a second proceed.
        val request: Request = try {
            val certHash = signingCertHash.bytes()
            require(certHash.size >= SALT_SIZE_BYTES) { "signing-cert hash too short" }
            HKDF.deriveKey(
                salt = certHash.copyOfRange(0, SALT_SIZE_BYTES),
                ikm = pepper,
                info = HKDF_INFO,
                output = keyBytes,
            )
            // Only attach the build-time UUID when the request does NOT already
            // carry a per-endpoint Lava-Auth key.  ApiBackedTrackerClient.withAuth()
            // sets that header (with the on-device handoff key) BEFORE the request
            // reaches interceptors; overwriting it here with the build-time UUID was
            // the root-cause of the weeks-long search-401 regression (H1 / 1072).
            //
            // Safety matrix:
            //  • on-device api-app path  — withAuth() already set the handoff key
            //    → interceptor sees it present → skips → key survives to the wire ✓
            //  • remote cloud-API path   — no pre-set Lava-Auth header exists
            //    → interceptor sees null → attaches build-time UUID as before ✓
            if (chain.request().header(fieldName) != null) {
                chain.request()
            } else {
                uuidBytes = AesGcm.decrypt(blob, keyBytes, nonce)
                val headerValue = Base64.getEncoder().encodeToString(uuidBytes)
                chain.request().newBuilder()
                    .header(fieldName, headerValue)
                    .build()
            }
        } catch (e: Exception) {
            // FAIL OPEN. Cert/blob drift (re-signed APK, androidTest build) makes
            // decrypt throw; a malformed-auth failure must not brick every request
            // on the lan client. Record a §6.AC non-fatal with the error CLASS only
            // — NEVER the blob, derived key, nonce, or header value (§6.H) — and
            // send the original request with no Lava-Auth header.
            loggerFactory?.get(LOG_TAG)?.e {
                "Lava-Auth decrypt failed; proceeding WITHOUT auth header (fail-open): " +
                    e.javaClass.simpleName
            }
            chain.request()
        } finally {
            keyBytes.fill(0)
            uuidBytes?.fill(0)
        }
        return chain.proceed(request)
    }

    /** Functional wrapper around [SigningCertProvider.sha256] so tests can inject a fixed hash. */
    fun interface SigningCertHash {
        fun bytes(): ByteArray
    }

    private companion object {
        const val KEY_SIZE_BYTES = 32
        const val SALT_SIZE_BYTES = 16
        const val LOG_TAG = "AuthInterceptor"
        val HKDF_INFO = "lava-auth-v1".toByteArray(Charsets.UTF_8)
    }
}
