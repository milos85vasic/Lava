package lava.network.impl

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM AEAD encrypt/decrypt.
 *
 * Tag length: 128 bits (16 bytes). Output of [encrypt] is
 * `ciphertext ‖ tag` (the JCE convention). Any tampering produces
 * `AEADBadTagException` at decrypt time — the tamper-detection guarantee
 * the AuthInterceptor relies on for the re-signed-APK attack vector.
 */
internal object AesGcm {
    private const val TAG_LEN_BITS = 128

    fun encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256-GCM key must be 32 bytes" }
        require(nonce.size == 12) { "AES-256-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN_BITS, nonce),
        )
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertextWithTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256-GCM key must be 32 bytes" }
        require(nonce.size == 12) { "AES-256-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN_BITS, nonce),
        )
        return cipher.doFinal(ciphertextWithTag)
    }
}
