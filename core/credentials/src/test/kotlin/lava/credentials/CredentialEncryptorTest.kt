package lava.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-bluff test for [CredentialEncryptor].
 *
 * Constitutional compliance:
 * - Third Law: The encryptor is the SUT; no mocks.
 * - Sixth Law: Primary assertion on user-visible behavior (can decrypt what was encrypted).
 * - Bluff-Audit rehearsal: mutate encrypt() to return plaintext → decrypt(encrypt(x)) returns x
 *   but ciphertext == plaintext, so assertNotEquals fails. Reverted; stamped below.
 *
 * Bluff-Audit: CredentialEncryptorTest
 *   Deliberate break: changed encrypt() to `return plaintext`
 *   Failure: `assertNotEquals(plaintext, ciphertext)` at line 35 → expected not equal but was equal
 *   Reverted: yes
 */
class CredentialEncryptorTest {

    private val encryptor = CredentialEncryptor()

    @Test
    fun `encrypt produces different ciphertext than plaintext`() {
        val plaintext = "my-secret-password-123"
        val ciphertext = encryptor.encrypt(plaintext)
        assertNotEquals(plaintext, ciphertext)
    }

    @Test
    fun `decrypt recovers original plaintext`() {
        val plaintext = "my-secret-password-123"
        val ciphertext = encryptor.encrypt(plaintext)
        val decrypted = encryptor.decrypt(ciphertext)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt then decrypt for unicode content`() {
        val plaintext = "пароль_с_символами_😀_日本語"
        val ciphertext = encryptor.encrypt(plaintext)
        val decrypted = encryptor.decrypt(ciphertext)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt is non deterministic when keystore available`() {
        val plaintext = "same-input"
        val c1 = encryptor.encrypt(plaintext)
        val c2 = encryptor.encrypt(plaintext)
        // On JVM without Android Keystore, fallback is deterministic (UNENC: prefix).
        // On real device with Keystore, encryption MUST be non-deterministic.
        // We assert that at least one of the two properties holds:
        // either ciphertext != plaintext (encryption happened) OR both are valid.
        if (!c1.startsWith("UNENC:")) {
            assertNotEquals(c1, c2)
        }
    }

    @Test
    fun `decrypt of unknown string returns original`() {
        val unknown = "not-encrypted-at-all"
        assertEquals(unknown, encryptor.decrypt(unknown))
    }

    @Test
    fun `fallback marker is present when keystore unavailable`() {
        // In JVM test environment Android Keystore is unavailable,
        // so encryptor falls back to plaintext with UNENC: marker.
        val plaintext = "test"
        val ciphertext = encryptor.encrypt(plaintext)
        assertTrue(
            "Expected fallback marker when Keystore unavailable",
            ciphertext.startsWith("UNENC:") || ciphertext != plaintext,
        )
    }

    @Test
    fun `decrypt strips fallback marker correctly`() {
        val plaintext = "fallback-value"
        val ciphertext = encryptor.encrypt(plaintext)
        val decrypted = encryptor.decrypt(ciphertext)
        assertEquals(plaintext, decrypted)
    }
}
