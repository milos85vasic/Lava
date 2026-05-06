package lava.network.impl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.crypto.AEADBadTagException

class AesGcmTest {

    @Test
    fun `round-trip recovers plaintext`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12) { 0x11 }
        val plaintext = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        )

        val ciphertext = AesGcm.encrypt(plaintext, key, nonce)
        assertEquals(plaintext.size + 16, ciphertext.size)

        val recovered = AesGcm.decrypt(ciphertext, key, nonce)
        assertArrayEquals(plaintext, recovered)
    }

    @Test(expected = AEADBadTagException::class)
    fun `tamper at last byte produces AEADBadTagException`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12) { 0x11 }
        val plaintext = byteArrayOf(0x01, 0x02, 0x03)

        val ciphertext = AesGcm.encrypt(plaintext, key, nonce)
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0x01).toByte()
        AesGcm.decrypt(ciphertext, key, nonce)
    }

    @Test(expected = AEADBadTagException::class)
    fun `wrong key produces AEADBadTagException`() {
        val key = ByteArray(32) { 0x42 }
        val wrongKey = ByteArray(32) { 0x43 }
        val nonce = ByteArray(12) { 0x11 }
        val plaintext = byteArrayOf(0x01, 0x02, 0x03)

        val ciphertext = AesGcm.encrypt(plaintext, key, nonce)
        AesGcm.decrypt(ciphertext, wrongKey, nonce)
    }

    @Test(expected = AEADBadTagException::class)
    fun `wrong nonce produces AEADBadTagException`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12) { 0x11 }
        val wrongNonce = ByteArray(12) { 0x22 }
        val plaintext = byteArrayOf(0x01, 0x02, 0x03)

        val ciphertext = AesGcm.encrypt(plaintext, key, nonce)
        AesGcm.decrypt(ciphertext, key, wrongNonce)
    }

    @Test
    fun `same key + nonce + different plaintext produces different ciphertext`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12) { 0x11 }

        val c1 = AesGcm.encrypt(byteArrayOf(0x01), key, nonce)
        val c2 = AesGcm.encrypt(byteArrayOf(0x02), key, nonce)
        assertNotEquals(c1.toList(), c2.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong key size rejected`() {
        AesGcm.encrypt(ByteArray(0), ByteArray(16), ByteArray(12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong nonce size rejected`() {
        AesGcm.encrypt(ByteArray(0), ByteArray(32), ByteArray(8))
    }
}
