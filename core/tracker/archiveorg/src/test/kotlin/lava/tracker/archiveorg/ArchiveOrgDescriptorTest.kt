package lava.tracker.archiveorg

import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveOrgDescriptorTest {

    @Test
    fun `trackerId is archiveorg and matches HasId#id`() {
        assertEquals("archiveorg", ArchiveOrgDescriptor.trackerId)
        assertEquals("archiveorg", ArchiveOrgDescriptor.id)
    }

    @Test
    fun `declares exactly 4 capabilities and excludes TORRENT_DOWNLOAD, MAGNET_LINK, COMMENTS, FAVORITES, AUTH_REQUIRED`() {
        val caps = ArchiveOrgDescriptor.capabilities
        assertEquals(4, caps.size)
        assertTrue(TrackerCapability.SEARCH in caps)
        assertTrue(TrackerCapability.BROWSE in caps)
        assertTrue(TrackerCapability.FORUM in caps)
        assertTrue(TrackerCapability.TOPIC in caps)
        assertFalse("TORRENT_DOWNLOAD must not be declared", TrackerCapability.TORRENT_DOWNLOAD in caps)
        assertFalse("MAGNET_LINK must not be declared", TrackerCapability.MAGNET_LINK in caps)
        assertFalse("COMMENTS must not be declared", TrackerCapability.COMMENTS in caps)
        assertFalse("FAVORITES must not be declared", TrackerCapability.FAVORITES in caps)
        assertFalse("AUTH_REQUIRED must not be declared", TrackerCapability.AUTH_REQUIRED in caps)
    }

    @Test
    fun `has 1 mirror with archive org as primary`() {
        val mirrors = ArchiveOrgDescriptor.baseUrls
        assertEquals(1, mirrors.size)
        val primary = mirrors.firstOrNull { it.isPrimary }
        assertNotNull("Exactly one primary mirror required", primary)
        assertEquals("https://archive.org", primary!!.url)
        assertEquals(0, primary.priority)
        assertEquals(Protocol.HTTPS, primary.protocol)
    }

    @Test
    fun `auth type is NONE`() {
        assertEquals(AuthType.NONE, ArchiveOrgDescriptor.authType)
    }

    @Test
    fun `encoding is UTF-8`() {
        assertEquals("UTF-8", ArchiveOrgDescriptor.encoding)
    }

    @Test
    fun `expectedHealthMarker is Internet Archive`() {
        assertEquals("Internet Archive", ArchiveOrgDescriptor.expectedHealthMarker)
    }

    @Test
    fun `display name is Internet Archive`() {
        assertEquals("Internet Archive", ArchiveOrgDescriptor.displayName)
    }
}
