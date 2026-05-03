package lava.tracker.nnmclub

import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NnmclubClientTest {

    @Test
    fun `getFeature resolves declared capabilities`() {
        val client = NnmclubClient(
            http = lava.tracker.nnmclub.http.NnmclubHttpClient(),
            search = lava.tracker.nnmclub.feature.NnmclubSearch(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubSearchParser(),
            ),
            browse = lava.tracker.nnmclub.feature.NnmclubBrowse(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubBrowseParser(),
            ),
            topic = lava.tracker.nnmclub.feature.NnmclubTopic(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubTopicParser(),
            ),
            comments = lava.tracker.nnmclub.feature.NnmclubComments(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
            ),
            auth = lava.tracker.nnmclub.feature.NnmclubAuth(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubLoginParser(),
            ),
            download = lava.tracker.nnmclub.feature.NnmclubDownload(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
            ),
        )

        assertNotNull(client.getFeature(SearchableTracker::class))
        assertNotNull(client.getFeature(BrowsableTracker::class))
        assertNotNull(client.getFeature(TopicTracker::class))
        assertNotNull(client.getFeature(CommentsTracker::class))
        assertNotNull(client.getFeature(AuthenticatableTracker::class))
        assertNotNull(client.getFeature(DownloadableTracker::class))
    }

    @Test
    fun `getFeature returns null for undeclared capabilities`() {
        val client = NnmclubClient(
            http = lava.tracker.nnmclub.http.NnmclubHttpClient(),
            search = lava.tracker.nnmclub.feature.NnmclubSearch(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubSearchParser(),
            ),
            browse = lava.tracker.nnmclub.feature.NnmclubBrowse(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubBrowseParser(),
            ),
            topic = lava.tracker.nnmclub.feature.NnmclubTopic(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubTopicParser(),
            ),
            comments = lava.tracker.nnmclub.feature.NnmclubComments(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
            ),
            auth = lava.tracker.nnmclub.feature.NnmclubAuth(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
                lava.tracker.nnmclub.parser.NnmclubLoginParser(),
            ),
            download = lava.tracker.nnmclub.feature.NnmclubDownload(
                lava.tracker.nnmclub.http.NnmclubHttpClient(),
            ),
        )

        assertNull(client.getFeature(lava.tracker.api.feature.FavoritesTracker::class))
    }
}
