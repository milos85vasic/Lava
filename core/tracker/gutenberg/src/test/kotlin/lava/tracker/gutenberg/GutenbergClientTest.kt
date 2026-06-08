package lava.tracker.gutenberg

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.gutenberg.http.GutenbergHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GutenbergClientTest {

    private val http = GutenbergHttpClient()

    @Test
    fun `getFeature resolves declared capabilities`() {
        val client = GutenbergClient(
            http = http,
            search = lava.tracker.gutenberg.feature.GutenbergSearch(http),
            browse = lava.tracker.gutenberg.feature.GutenbergBrowse(http),
            topic = lava.tracker.gutenberg.feature.GutenbergTopic(http),
            download = lava.tracker.gutenberg.feature.GutenbergDownload(http),
        )

        assertNotNull(client.getFeature(SearchableTracker::class))
        assertNotNull(client.getFeature(BrowsableTracker::class))
        assertNotNull(client.getFeature(TopicTracker::class))
    }

    @Test
    fun `getFeature returns null for unsupported capabilities`() {
        val client = GutenbergClient(
            http = http,
            search = lava.tracker.gutenberg.feature.GutenbergSearch(http),
            browse = lava.tracker.gutenberg.feature.GutenbergBrowse(http),
            topic = lava.tracker.gutenberg.feature.GutenbergTopic(http),
            download = lava.tracker.gutenberg.feature.GutenbergDownload(http),
        )

        assertNull(client.getFeature(lava.tracker.api.feature.CommentsTracker::class))
        assertNull(client.getFeature(lava.tracker.api.feature.AuthenticatableTracker::class))
        assertNull(client.getFeature(lava.tracker.api.feature.FavoritesTracker::class))
        // Clause 6.E: gutenberg serves HTTP e-books, not `.torrent` files, so
        // DownloadableTracker is intentionally null (TORRENT_DOWNLOAD undeclared).
        assertNull(client.getFeature(DownloadableTracker::class))
    }
}
