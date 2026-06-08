package lava.tracker.iptorrents

import io.mockk.mockk
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Constitutional clause 6.E — Capability-Honesty for the IPTorrents provider.
 *
 * Asserts the exact 6.E contract: EVERY capability [IPTorrentsDescriptor]
 * declares (SEARCH, TORRENT_DOWNLOAD, MAGNET_LINK) resolves to a NON-NULL feature
 * via the REAL production [IPTorrentsClient.getFeature]; and every capability it
 * does NOT declare (BROWSE/TOPIC/COMMENTS/FAVORITES/AUTH) resolves to null — so
 * the provider cannot advertise a feature it has no `/jackett/search`-route path
 * to back (the declared-but-empty bluff).
 *
 * SUT = [IPTorrentsClient.getFeature] routing + the real [IPTorrentsDescriptor].
 * The two feature impls are boundaries BELOW the SUT (getFeature only routes
 * references, it never invokes a feature method), so relaxed mockk fakes are
 * permitted there (Seventh Law clause 4).
 *
 * Falsifiability (§6.J clause 2): if IPTorrentsClient.getFeature(SearchableTracker)
 * were changed to return null (dropping the SEARCH wiring), the first assertion
 * here fails with "expected SearchableTracker to resolve". If a non-declared
 * capability (e.g. AUTH_REQUIRED) were added to the descriptor without a wired
 * AuthenticatableTracker, [declaredCapabilitiesAllResolve] would flag it.
 */
class IPTorrentsCapabilityHonestyTest {

    private fun client(): IPTorrentsClient = IPTorrentsClient(
        search = mockk(relaxed = true),
        download = mockk(relaxed = true),
    )

    private val capabilityToFeature = mapOf(
        TrackerCapability.SEARCH to SearchableTracker::class,
        TrackerCapability.TORRENT_DOWNLOAD to DownloadableTracker::class,
        TrackerCapability.MAGNET_LINK to DownloadableTracker::class,
    )

    @Test
    fun `every declared capability resolves to a non-null feature`() {
        val c = client()
        val violations = mutableListOf<String>()
        for (capability in IPTorrentsDescriptor.capabilities) {
            val feature = capabilityToFeature[capability]
                ?: error("Test out of date: IPTorrents declares $capability with no feature mapping in this test")
            if (c.getFeature(feature) == null) {
                violations += "declares $capability but getFeature(${feature.simpleName}) returned null"
            }
        }
        assertNotNull(c.getFeature(SearchableTracker::class))
        assertNotNull(c.getFeature(DownloadableTracker::class))
        org.junit.Assert.assertTrue(
            "§6.E Capability-Honesty violations for IPTorrents:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `non-declared capabilities resolve to null — no declared-but-empty bluff`() {
        val c = client()
        // None of these capabilities are declared, so none of their features
        // may resolve (no jackett browse/topic/comments/favorites/auth route).
        assertNull(c.getFeature(BrowsableTracker::class))
        assertNull(c.getFeature(TopicTracker::class))
        assertNull(c.getFeature(CommentsTracker::class))
        assertNull(c.getFeature(FavoritesTracker::class))
        assertNull(c.getFeature(AuthenticatableTracker::class))
    }

    @Test
    fun `descriptor declares exactly SEARCH, TORRENT_DOWNLOAD, MAGNET_LINK`() {
        org.junit.Assert.assertEquals(
            setOf(
                TrackerCapability.SEARCH,
                TrackerCapability.TORRENT_DOWNLOAD,
                TrackerCapability.MAGNET_LINK,
            ),
            IPTorrentsDescriptor.capabilities,
        )
    }
}
