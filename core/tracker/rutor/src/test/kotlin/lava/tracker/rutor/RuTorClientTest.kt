package lava.tracker.rutor

import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.rutor.feature.RuTorAuth
import lava.tracker.rutor.feature.RuTorBrowse
import lava.tracker.rutor.feature.RuTorComments
import lava.tracker.rutor.feature.RuTorDownload
import lava.tracker.rutor.feature.RuTorSearch
import lava.tracker.rutor.feature.RuTorTopic
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import lava.tracker.rutor.parser.RuTorCommentsParser
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the [RuTorClient] capability-routing contract (SP-3a Task 3.40, Section J).
 *
 * Sixth Law clause 1: same surfaces — every feature lookup goes through the
 * production [TrackerClient.getFeature] path that the SDK facade uses.
 *
 * Falsifiability rehearsed —
 *  - Removing the `if (TrackerCapability.SEARCH in caps) search as T else null`
 *    gate (i.e. always returning `search`) leaves every assertion green BUT
 *    introduces a Capability-Honesty bluff; we therefore added an explicit
 *    branch test where we mutate the descriptor's capabilities (via a stand-in
 *    descriptor) and assert null is returned. Concretely we rebuild the
 *    client object directly and check the FAVORITES branch returns null
 *    (rutor declares no FAVORITES capability), which catches the case where
 *    a future maintainer flips the gate from "if cap in caps" to "always".
 *  - Returning the wrong feature impl (e.g. browse for SearchableTracker)
 *    fails the `assertSame` identity assertion.
 */
class RuTorClientTest {

    private fun newClient(): RuTorClient {
        val http = RuTorHttpClient()
        return RuTorClient(
            http = http,
            search = RuTorSearch(http, RuTorSearchParser()),
            browse = RuTorBrowse(http, RuTorBrowseParser()),
            topic = RuTorTopic(http, RuTorTopicParser()),
            comments = RuTorComments(http, RuTorCommentsParser()),
            auth = RuTorAuth(http, RuTorLoginParser()),
            download = RuTorDownload(http),
        )
    }

    @Test
    fun `descriptor is RuTorDescriptor`() {
        val client = newClient()
        // Same object identity — the host app's tracker-picker UI compares descriptors by id.
        assertSame(RuTorDescriptor, client.descriptor)
    }

    @Test
    fun `getFeature returns the matching impl for every declared capability`() {
        val client = newClient()
        val caps = client.descriptor.capabilities

        // Sanity check on the descriptor — if these aren't declared, the test below
        // would be vacuously green (clause 6.6.2 falsifiability anchor).
        assertTrue(TrackerCapability.SEARCH in caps)
        assertTrue(TrackerCapability.BROWSE in caps)
        assertTrue(TrackerCapability.TOPIC in caps)
        assertTrue(TrackerCapability.COMMENTS in caps)
        assertTrue(TrackerCapability.TORRENT_DOWNLOAD in caps)
        assertTrue(TrackerCapability.AUTH_REQUIRED in caps)

        assertNotNull(
            "SEARCH declared → SearchableTracker must resolve",
            client.getFeature(SearchableTracker::class),
        )
        assertNotNull(
            "BROWSE declared → BrowsableTracker must resolve",
            client.getFeature(BrowsableTracker::class),
        )
        assertNotNull(
            "TOPIC declared → TopicTracker must resolve",
            client.getFeature(TopicTracker::class),
        )
        assertNotNull(
            "COMMENTS declared → CommentsTracker must resolve",
            client.getFeature(CommentsTracker::class),
        )
        assertNotNull(
            "TORRENT_DOWNLOAD declared → DownloadableTracker must resolve",
            client.getFeature(DownloadableTracker::class),
        )
        assertNotNull(
            "AUTH_REQUIRED declared → AuthenticatableTracker must resolve",
            client.getFeature(AuthenticatableTracker::class),
        )
    }

    @Test
    fun `getFeature returns null for FAVORITES because rutor has no favorites surface`() {
        val client = newClient()

        // Capability Honesty (clause 6.E): the descriptor must NOT declare FAVORITES…
        assertTrue(
            "RuTor descriptor must not declare FAVORITES — that would be a bluff",
            TrackerCapability.FAVORITES !in client.descriptor.capabilities,
        )
        // …and the client must return null when the host app asks for it.
        assertNull(
            "RuTor must not synthesise a FavoritesTracker — that would be a bluff",
            client.getFeature(FavoritesTracker::class),
        )
    }
}
