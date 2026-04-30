package lava.tracker.rutor

import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTorDescriptorTest {

    @Test
    fun `trackerId is rutor and matches HasId#id`() {
        assertEquals("rutor", RuTorDescriptor.trackerId)
        assertEquals("rutor", RuTorDescriptor.id)
    }

    @Test
    fun `declares exactly 8 capabilities and excludes FORUM, FAVORITES, CAPTCHA_LOGIN`() {
        val caps = RuTorDescriptor.capabilities
        assertEquals(8, caps.size)
        assertTrue(TrackerCapability.SEARCH in caps)
        assertTrue(TrackerCapability.BROWSE in caps)
        assertTrue(TrackerCapability.TOPIC in caps)
        assertTrue(TrackerCapability.COMMENTS in caps)
        assertTrue(TrackerCapability.TORRENT_DOWNLOAD in caps)
        assertTrue(TrackerCapability.MAGNET_LINK in caps)
        assertTrue(TrackerCapability.RSS in caps)
        assertTrue(TrackerCapability.AUTH_REQUIRED in caps)
        assertFalse("FORUM must not be declared", TrackerCapability.FORUM in caps)
        assertFalse("FAVORITES must not be declared", TrackerCapability.FAVORITES in caps)
        assertFalse("CAPTCHA_LOGIN must not be declared", TrackerCapability.CAPTCHA_LOGIN in caps)
    }

    @Test
    fun `has 5 mirrors with rutor info as primary`() {
        val mirrors = RuTorDescriptor.baseUrls
        assertEquals(5, mirrors.size)
        val primary = mirrors.firstOrNull { it.isPrimary }
        assertNotNull("Exactly one primary mirror required", primary)
        assertEquals("https://rutor.info", primary!!.url)
        assertEquals(0, primary.priority)
        assertEquals(Protocol.HTTPS, primary.protocol)
        // Mirror priorities ascend 0..4 with no duplicates.
        val priorities = mirrors.map { it.priority }.sorted()
        assertEquals(listOf(0, 1, 2, 3, 4), priorities)
        // The only HTTP mirror is the ipv6-only fallback.
        val httpMirrors = mirrors.filter { it.protocol == Protocol.HTTP }
        assertEquals(1, httpMirrors.size)
        assertEquals("http://6tor.org", httpMirrors.single().url)
        assertEquals("ipv6-only", httpMirrors.single().region)
    }

    @Test
    fun `auth type is FORM_LOGIN (no captcha)`() {
        assertEquals(AuthType.FORM_LOGIN, RuTorDescriptor.authType)
    }

    @Test
    fun `encoding is UTF-8 (no Windows-1251 transcoding)`() {
        assertEquals("UTF-8", RuTorDescriptor.encoding)
    }

    @Test
    fun `expectedHealthMarker is RuTor for primary-page substring probe`() {
        assertEquals("RuTor", RuTorDescriptor.expectedHealthMarker)
    }

    @Test
    fun `display name uses RuTor info casing`() {
        assertEquals("RuTor.info", RuTorDescriptor.displayName)
    }
}
