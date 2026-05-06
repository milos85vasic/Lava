package lava.network.impl

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 per RFC 5869.
 *
 * Used by [AuthInterceptor] (Phase 10) to derive the per-build AES-256
 * key from `(signing-cert-hash, pepper)`. The build-time codegen task
 * (Phase 11) must implement an algorithmically identical version; a
 * divergence breaks AEAD round-trip with `AEADBadTagException`.
 */
internal object HKDF {
    fun deriveKey(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        output: ByteArray,
    ) {
        val hmac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        hmac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        val n = (output.size + 31) / 32
        require(n in 1..255) { "HKDF: output length must be 1..255*32 bytes" }
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            hmac.update(t)
            hmac.update(info)
            hmac.update(byteArrayOf(i.toByte()))
            t = hmac.doFinal()
            val take = minOf(32, output.size - pos)
            System.arraycopy(t, 0, output, pos, take)
            pos += take
        }
    }
}
