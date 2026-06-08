package lava.tracker.gutenberg

import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.gutenberg.feature.GutenbergBrowse
import lava.tracker.gutenberg.feature.GutenbergDownload
import lava.tracker.gutenberg.feature.GutenbergSearch
import lava.tracker.gutenberg.feature.GutenbergTopic
import lava.tracker.gutenberg.http.GutenbergHttpClient
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain Project Gutenberg client.
 *
 * Wraps the feature impls that match the capabilities [GutenbergDescriptor]
 * declares (SEARCH, BROWSE, TOPIC) and exposes them via
 * [TrackerClient.getFeature].
 *
 * Capability Honesty (clause 6.E): the HTTP-download impl is constructed but
 * deliberately NOT exposed via getFeature, because Project Gutenberg serves
 * EPUB / plain-text / HTML e-books over HTTP, not `.torrent` files —
 * [TrackerCapability] has no HTTP_DOWNLOAD value today, so declaring
 * TORRENT_DOWNLOAD would be a bluff. Mirrors the Internet Archive provider.
 */
class GutenbergClient @Inject constructor(
    private val http: GutenbergHttpClient,
    private val search: GutenbergSearch,
    private val browse: GutenbergBrowse,
    private val topic: GutenbergTopic,
    private val download: GutenbergDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = GutenbergDescriptor

    override suspend fun healthCheck(): Boolean = try {
        http.get("https://gutendex.com/books/").use { response ->
            response.isSuccessful
        }
    } catch (_: Throwable) {
        false
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? {
        val caps = descriptor.capabilities
        return when (featureClass) {
            SearchableTracker::class -> if (TrackerCapability.SEARCH in caps) search as T else null
            BrowsableTracker::class -> if (TrackerCapability.BROWSE in caps) browse as T else null
            TopicTracker::class -> if (TrackerCapability.TOPIC in caps) topic as T else null
            // HTTP e-book download is implemented (see GutenbergDownload) but
            // not declared as a capability — TrackerCapability lacks
            // HTTP_DOWNLOAD today, and the artifact is not a `.torrent`
            // (clause 6.E). The `download` field is intentionally unused here.
            // HTTP e-book download is implemented (see GutenbergDownload) but
            // not declared as a capability — TrackerCapability lacks
            // HTTP_DOWNLOAD today, and the artifact is not a `.torrent`
            // (clause 6.E). The `download` field is intentionally unused here.
            DownloadableTracker::class -> null
            else -> null
        }
    }

    override fun close() {
        // No-op; OkHttp client is a singleton managed by Hilt.
    }
}
