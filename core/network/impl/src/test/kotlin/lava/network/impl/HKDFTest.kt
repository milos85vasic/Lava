package lava.network.impl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HKDFTest {

    /**
     * RFC 5869 §A.1 — Test Case 1 (Basic test case with SHA-256).
     *   IKM  = 0x0b * 22
     *   salt = 0x000102030405060708090a0b0c
     *   info = 0xf0f1f2f3f4f5f6f7f8f9
     *   L    = 42
     *   OKM  = 0x3cb25f25faacd57a90434f64d0362f2a 2d2d0a90cf1a5a4c5db02d56ecc4c5bf 34007208d5b887185865
     */
    @Test
    fun `RFC 5869 vector A1 produces expected OKM`() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c,
        )
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(),
            0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf8.toByte(), 0xf9.toByte(),
        )
        val expected = byteArrayOf(
            0x3c, 0xb2.toByte(), 0x5f, 0x25, 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a,
            0x90.toByte(), 0x43, 0x4f, 0x64, 0xd0.toByte(), 0x36, 0x2f, 0x2a,
            0x2d, 0x2d, 0x0a, 0x90.toByte(), 0xcf.toByte(), 0x1a, 0x5a, 0x4c,
            0x5d, 0xb0.toByte(), 0x2d, 0x56, 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(),
            0x34, 0x00, 0x72, 0x08, 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18,
            0x58, 0x65,
        )
        val out = ByteArray(42)
        HKDF.deriveKey(salt = salt, ikm = ikm, info = info, output = out)
        assertArrayEquals(expected, out)
    }

    @Test
    fun `derives 32-byte AES-256 key without errors`() {
        val out = ByteArray(32)
        HKDF.deriveKey(
            salt = ByteArray(16) { 0x42 },
            ikm = ByteArray(32) { 0x77 },
            info = "lava-auth-v1".toByteArray(Charsets.UTF_8),
            output = out,
        )
        var allZero = true
        for (b in out) if (b != 0.toByte()) {
            allZero = false
            break
        }
        assertFalse("HKDF produced all-zero output", allZero)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects output greater than 255 blocks`() {
        val out = ByteArray(8161)
        HKDF.deriveKey(
            salt = ByteArray(16),
            ikm = ByteArray(32),
            info = byteArrayOf(),
            output = out,
        )
    }
}
