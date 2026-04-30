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
    fun `declares all 10 RuTracker capabilities`() {
        // LF-5 RESOLVED (2026-04-30): UPLOAD and USER_PROFILE removed
        // from the descriptor because :core:tracker:api/feature/ has no
        // UploadableTracker or ProfileTracker interface — getFeature<T>()
        // could never return non-null for them. Restoring them requires
        // either adding the interfaces (scope-creep beyond SP-3a) or
        // accepting the 6.E violation (forbidden).
        assertEquals(10, RuTrackerDescriptor.capabilities.size)
    }

    @Test
    fun `LF-5 RESOLVED — UPLOAD and USER_PROFILE are NOT declared (no feature interface)`() {
        assertEquals(false, RuTrackerDescriptor.capabilities.contains(lava.tracker.api.TrackerCapability.UPLOAD))
        assertEquals(false, RuTrackerDescriptor.capabilities.contains(lava.tracker.api.TrackerCapability.USER_PROFILE))
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
