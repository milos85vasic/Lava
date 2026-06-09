package lava.tracker.api

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerCapabilityTest {
    @Test
    fun `enum has 14 values matching spec`() {
        assertEquals(14, TrackerCapability.entries.size)
    }

    @Test
    fun `contains all named capabilities`() {
        val names = TrackerCapability.entries.map { it.name }.toSet()
        assertEquals(
            setOf(
                "SEARCH", "BROWSE", "FORUM", "TOPIC", "COMMENTS", "FAVORITES",
                "TORRENT_DOWNLOAD", "HTTP_DOWNLOAD", "MAGNET_LINK", "AUTH_REQUIRED",
                "CAPTCHA_LOGIN", "RSS", "UPLOAD", "USER_PROFILE",
            ),
            names,
        )
    }
}
