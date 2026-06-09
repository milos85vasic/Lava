package lava.tracker.gutenberg

import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import org.junit.Assert.assertEquals
import org.junit.Test

class GutenbergDescriptorTest {

    @Test
    fun `descriptor declares correct metadata`() {
        assertEquals("gutenberg", GutenbergDescriptor.trackerId)
        assertEquals("Project Gutenberg", GutenbergDescriptor.displayName)
        assertEquals(AuthType.NONE, GutenbergDescriptor.authType)
        assertEquals("UTF-8", GutenbergDescriptor.encoding)
    }

    @Test
    fun `capabilities are honest`() {
        val caps = GutenbergDescriptor.capabilities
        assertEquals(
            setOf(
                TrackerCapability.SEARCH,
                TrackerCapability.BROWSE,
                TrackerCapability.TOPIC,
                // LVA-044: HTTP_DOWNLOAD is the honest home for gutenberg's HTTP
                // e-book download (EPUB / text / HTML), NOT TORRENT_DOWNLOAD —
                // gutenberg serves no `.torrent` files (clause 6.E). See
                // GutenbergCapabilityHonestyTest + GutenbergHttpDownloadRealStackTest.
                TrackerCapability.HTTP_DOWNLOAD,
            ),
            caps,
        )
    }
}
