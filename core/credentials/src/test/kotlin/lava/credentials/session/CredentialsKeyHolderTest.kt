package lava.credentials.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsKeyHolderTest {
    @Test fun `starts locked`() {
        val h = CredentialsKeyHolder()
        assertNull(h.getOrNull())
        assertFalse(h.isUnlocked())
    }

    @Test fun `unlock then getOrNull returns same key`() {
        val h = CredentialsKeyHolder()
        val k = ByteArray(32) { 1 }
        h.unlock(k)
        assertArrayEquals(k, h.getOrNull())
        assertTrue(h.isUnlocked())
    }

    @Test fun `lock zeroes the key`() {
        val h = CredentialsKeyHolder()
        val k = ByteArray(32) { 7 }
        h.unlock(k)
        h.lock()
        assertNull(h.getOrNull())
        assertTrue(k.all { it == 0.toByte() })
    }
}
