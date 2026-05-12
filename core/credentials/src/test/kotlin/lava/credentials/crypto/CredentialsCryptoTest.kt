package lava.credentials.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CredentialsCryptoTest {

    private val salt = ByteArray(32) { it.toByte() }
    private val passphrase = "correct horse battery staple"

    @Test
    fun `derive deterministic from same passphrase and salt`() {
        val k1 = CredentialsCrypto.deriveKey(passphrase, salt)
        val k2 = CredentialsCrypto.deriveKey(passphrase, salt)
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
    }

    @Test
    fun `verifier round trips and rejects wrong passphrase`() {
        val key = CredentialsCrypto.deriveKey(passphrase, salt)
        val verifier = CredentialsCrypto.makeVerifier(key)
        assertTrue(CredentialsCrypto.checkVerifier(key, verifier))
        val wrong = CredentialsCrypto.deriveKey("wrong passphrase", salt)
        assertFalse(CredentialsCrypto.checkVerifier(wrong, verifier))
    }

    @Test
    fun `encrypt then decrypt round trips`() {
        val key = CredentialsCrypto.deriveKey(passphrase, salt)
        val plain = "hunter2".toByteArray(Charsets.UTF_8)
        val ct = CredentialsCrypto.encrypt(key, plain)
        val pt = CredentialsCrypto.decrypt(key, ct)
        assertArrayEquals(plain, pt)
    }

    @Test
    fun `decrypt with wrong key throws AEADBadTagException`() {
        val key = CredentialsCrypto.deriveKey(passphrase, salt)
        val wrong = CredentialsCrypto.deriveKey("wrong", salt)
        val ct = CredentialsCrypto.encrypt(key, "hunter2".toByteArray())
        try {
            CredentialsCrypto.decrypt(wrong, ct)
            fail("expected AEADBadTagException")
        } catch (e: javax.crypto.AEADBadTagException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `tampered ciphertext throws AEADBadTagException`() {
        val key = CredentialsCrypto.deriveKey(passphrase, salt)
        val ct = CredentialsCrypto.encrypt(key, "hunter2".toByteArray())
        ct[20] = ct[20].inc()
        try {
            CredentialsCrypto.decrypt(key, ct)
            fail("expected AEADBadTagException")
        } catch (e: javax.crypto.AEADBadTagException) {
            assertNotNull(e)
        }
    }
}
