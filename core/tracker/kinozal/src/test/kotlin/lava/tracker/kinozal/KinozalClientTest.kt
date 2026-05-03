package lava.tracker.kinozal

import lava.tracker.api.TrackerCapability
import org.junit.Assert.assertTrue
import org.junit.Test

class KinozalClientTest {
    @Test
    fun `descriptor capabilities are honest`() {
        val caps = KinozalDescriptor.capabilities
        assertTrue(TrackerCapability.SEARCH in caps)
        assertTrue(TrackerCapability.BROWSE in caps)
        assertTrue(TrackerCapability.TOPIC in caps)
        assertTrue(TrackerCapability.TORRENT_DOWNLOAD in caps)
        assertTrue(TrackerCapability.MAGNET_LINK in caps)
        assertTrue(TrackerCapability.AUTH_REQUIRED in caps)
    }
}
