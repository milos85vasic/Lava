package lava.tracker.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-Bluff: primary assertions are on the MAPPED user-visible descriptor
 * state (the capability Set, the AuthType, the primary MirrorUrl) — the same
 * values the provider-list UI + ApiBackedTrackerClient gate on. The
 * load-bearing case is [unknownCapabilityIsDroppedNotThrown]: a forward-compat
 * capability string MUST be dropped, not crash the client.
 */
class RemoteTrackerDescriptorTest {

    @Test
    fun mapsAllKnownFields() {
        val descriptor = RemoteTrackerDescriptor.from(
            trackerId = "rutracker",
            displayName = "RuTracker.org",
            capabilities = listOf("SEARCH", "BROWSE", "TORRENT_DOWNLOAD", "CAPTCHA_LOGIN"),
            authType = "CAPTCHA_LOGIN",
            baseUrls = listOf("https://rutracker.org", "https://rutracker.net"),
            encoding = "Windows-1251",
            supportsAnonymous = false,
        )

        assertEquals("rutracker", descriptor.trackerId)
        assertEquals("rutracker", descriptor.id)
        assertEquals("RuTracker.org", descriptor.displayName)
        assertEquals(
            setOf(
                TrackerCapability.SEARCH,
                TrackerCapability.BROWSE,
                TrackerCapability.TORRENT_DOWNLOAD,
                TrackerCapability.CAPTCHA_LOGIN,
            ),
            descriptor.capabilities,
        )
        assertEquals(AuthType.CAPTCHA_LOGIN, descriptor.authType)
        assertEquals("Windows-1251", descriptor.encoding)
        assertFalse(descriptor.supportsAnonymous)

        // baseUrls: first is primary, priority follows index
        assertEquals(2, descriptor.baseUrls.size)
        assertEquals("https://rutracker.org", descriptor.baseUrls[0].url)
        assertTrue(descriptor.baseUrls[0].isPrimary)
        assertEquals(0, descriptor.baseUrls[0].priority)
        assertFalse(descriptor.baseUrls[1].isPrimary)
        assertEquals(1, descriptor.baseUrls[1].priority)

        // API vouches for the provider.
        assertTrue(descriptor.apiSupported)
        assertTrue(descriptor.verified)
    }

    @Test
    fun unknownCapabilityIsDroppedNotThrown() {
        val warnings = mutableListOf<String>()

        // No throw despite the bogus capability string in the middle of the list.
        val descriptor = RemoteTrackerDescriptor.from(
            trackerId = "1337x",
            displayName = "1337x",
            capabilities = listOf("SEARCH", "WARP_DRIVE_DOWNLOAD", "MAGNET_LINK"),
            authType = "NONE",
            baseUrls = emptyList(),
            encoding = "UTF-8",
            supportsAnonymous = true,
            warn = { warnings.add(it) },
        )

        // Known caps survive; the unknown one is gone — not a crash, not a null entry.
        assertEquals(
            setOf(TrackerCapability.SEARCH, TrackerCapability.MAGNET_LINK),
            descriptor.capabilities,
        )
        assertTrue(warnings.any { it.contains("WARP_DRIVE_DOWNLOAD") })
        assertEquals(AuthType.NONE, descriptor.authType)
        assertTrue(descriptor.supportsAnonymous)
    }

    @Test
    fun unknownAuthTypeFallsBackToNone() {
        val warnings = mutableListOf<String>()

        val descriptor = RemoteTrackerDescriptor.from(
            trackerId = "future",
            displayName = "Future Provider",
            capabilities = listOf("SEARCH"),
            authType = "QUANTUM_HANDSHAKE",
            baseUrls = listOf("https://future.example"),
            encoding = "UTF-8",
            supportsAnonymous = false,
            warn = { warnings.add(it) },
        )

        assertEquals(AuthType.NONE, descriptor.authType)
        assertTrue(warnings.any { it.contains("QUANTUM_HANDSHAKE") })
    }

    @Test
    fun capabilityParseIsCaseInsensitiveAndTrimmed() {
        assertEquals(TrackerCapability.SEARCH, RemoteTrackerDescriptor.parseCapability(" search "))
        assertEquals(AuthType.FORM_LOGIN, RemoteTrackerDescriptor.parseAuthType("form_login"))
        assertEquals(null, RemoteTrackerDescriptor.parseCapability("nope"))
        assertEquals(null, RemoteTrackerDescriptor.parseAuthType("nope"))
    }
}
