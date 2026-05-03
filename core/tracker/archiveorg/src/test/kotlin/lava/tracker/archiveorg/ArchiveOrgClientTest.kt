package lava.tracker.archiveorg

import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.archiveorg.feature.ArchiveOrgBrowse
import lava.tracker.archiveorg.feature.ArchiveOrgDownload
import lava.tracker.archiveorg.feature.ArchiveOrgSearch
import lava.tracker.archiveorg.feature.ArchiveOrgTopic
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveOrgClientTest {

    private fun newClient(): ArchiveOrgClient {
        val http = ArchiveOrgHttpClient()
        return ArchiveOrgClient(
            http = http,
            search = ArchiveOrgSearch(http),
            browse = ArchiveOrgBrowse(http),
            topic = ArchiveOrgTopic(http),
            download = ArchiveOrgDownload(http),
        )
    }

    @Test
    fun `descriptor is ArchiveOrgDescriptor`() {
        val client = newClient()
        assertSame(ArchiveOrgDescriptor, client.descriptor)
    }

    @Test
    fun `getFeature returns the matching impl for every declared capability`() {
        val client = newClient()
        val caps = client.descriptor.capabilities

        assertTrue(TrackerCapability.SEARCH in caps)
        assertTrue(TrackerCapability.BROWSE in caps)
        assertTrue(TrackerCapability.FORUM in caps)
        assertTrue(TrackerCapability.TOPIC in caps)

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
    }

    @Test
    fun `getFeature returns null for unsupported capabilities`() {
        val client = newClient()

        assertNull(
            "AUTH_REQUIRED not declared → AuthenticatableTracker must be null",
            client.getFeature(AuthenticatableTracker::class),
        )
        assertNull(
            "COMMENTS not declared → CommentsTracker must be null",
            client.getFeature(CommentsTracker::class),
        )
        assertNull(
            "FAVORITES not declared → FavoritesTracker must be null",
            client.getFeature(FavoritesTracker::class),
        )
        assertNull(
            "TORRENT_DOWNLOAD not declared → DownloadableTracker must be null",
            client.getFeature(DownloadableTracker::class),
        )
    }
}
