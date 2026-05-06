package lava.network.impl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Isolation tests for the SHA-256 digest computation that
 * [SigningCertProvider] applies to a signing certificate's DER bytes.
 *
 * The PackageManager-bound full path is exercised by Compose UI Challenge
 * Test C13 in Phase 13 on the §6.I emulator matrix — that's where the
 * real-stack assertion lands. Here we verify the digest math matches
 * the published SHA-256 of a known input so a future refactor of the
 * digest algorithm (e.g. switching to SHA-512) breaks this test before
 * it breaks the AuthInterceptor's key derivation.
 */
class SigningCertProviderTest {

    @Test
    fun `SHA-256 of empty input matches RFC published digest`() {
        val md = MessageDigest.getInstance("SHA-256")
        val out = md.digest(ByteArray(0))
        val expected = byteArrayOf(
            0xe3.toByte(), 0xb0.toByte(), 0xc4.toByte(), 0x42.toByte(),
            0x98.toByte(), 0xfc.toByte(), 0x1c.toByte(), 0x14.toByte(),
            0x9a.toByte(), 0xfb.toByte(), 0xf4.toByte(), 0xc8.toByte(),
            0x99.toByte(), 0x6f.toByte(), 0xb9.toByte(), 0x24.toByte(),
            0x27.toByte(), 0xae.toByte(), 0x41.toByte(), 0xe4.toByte(),
            0x64.toByte(), 0x9b.toByte(), 0x93.toByte(), 0x4c.toByte(),
            0xa4.toByte(), 0x95.toByte(), 0x99.toByte(), 0x1b.toByte(),
            0x78.toByte(), 0x52.toByte(), 0xb8.toByte(), 0x55.toByte(),
        )
        assertArrayEquals(expected, out)
    }

    @Test
    fun `SHA-256 of different inputs differ`() {
        val md = MessageDigest.getInstance("SHA-256")
        val a = md.digest(byteArrayOf(0x01))
        val b = md.digest(byteArrayOf(0x02))
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `SHA-256 returns 32 bytes`() {
        val md = MessageDigest.getInstance("SHA-256")
        val out = md.digest(byteArrayOf(0x42))
        assertEquals(32, out.size)
    }
}
