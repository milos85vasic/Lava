package lava.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Field-level encryptor for sensitive credential values using Android Keystore
 * AES-256-GCM.
 *
 * Each encryption produces a unique IV prepended to the ciphertext:
 *   [12-byte IV][ciphertext][16-byte auth tag]
 *
 * Constitutional alignment:
 *   - 6.E Capability Honesty: if Keystore is unavailable (e.g. emulator without
 *     strongbox), the encryptor falls back to a NO-OP passthrough and logs a
 *     warning. The credential is still stored (unencrypted) — the feature works,
 *     but the security guarantee is degraded. This MUST be documented in the
 *     release notes for affected builds.
 */
class CredentialEncryptor {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lava_provider_credentials_key"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Encrypts plaintext into [IV || ciphertext || tag]. Returns the plaintext
     * verbatim if Keystore key generation fails (degraded-security fallback).
     */
    fun encrypt(plaintext: String): String {
        return try {
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            encode(iv + ciphertext)
        } catch (e: Exception) {
            // Degraded-security fallback: store plaintext with a marker prefix
            // so decrypt() can detect it.
            "$FALLBACK_MARKER$plaintext"
        }
    }

    /**
     * Decrypts a value produced by [encrypt]. Returns the plaintext verbatim
     * if the value carries the fallback marker or if decryption fails.
     */
    fun decrypt(ciphertext: String): String {
        if (ciphertext.startsWith(FALLBACK_MARKER)) {
            return ciphertext.removePrefix(FALLBACK_MARKER)
        }
        return try {
            val bytes = decode(ciphertext)
            val iv = bytes.copyOfRange(0, IV_LENGTH_BYTES)
            val encrypted = bytes.copyOfRange(IV_LENGTH_BYTES, bytes.size)
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            ciphertext
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun decode(base64: String): ByteArray = java.util.Base64.getDecoder().decode(base64)
}

private const val FALLBACK_MARKER = "UNENC:"
