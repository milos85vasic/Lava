package lava.tracker.rutracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTrackerDescriptorTest {

    @Test
    fun `id matches trackerId`() {
        assertEquals("rutracker", RuTrackerDescriptor.id)
        assertEquals(RuTrackerDescriptor.trackerId, RuTrackerDescriptor.id)
    }

    @Test
    fun `declares all 12 RuTracker capabilities`() {
        assertEquals(12, RuTrackerDescriptor.capabilities.size)
    }

    @Test
    fun `primary mirror is rutracker_org`() {
        val primary = RuTrackerDescriptor.baseUrls.first { it.isPrimary }
        assertEquals("https://rutracker.org", primary.url)
    }

    @Test
    fun `encoding is Windows-1251`() {
        assertEquals("Windows-1251", RuTrackerDescriptor.encoding)
    }

    @Test
    fun `health marker is non-blank`() {
        assertTrue(RuTrackerDescriptor.expectedHealthMarker.isNotBlank())
    }
}
