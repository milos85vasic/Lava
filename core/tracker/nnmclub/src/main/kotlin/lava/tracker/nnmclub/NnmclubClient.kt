package lava.tracker.nnmclub

import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.nnmclub.feature.NnmclubAuth
import lava.tracker.nnmclub.feature.NnmclubBrowse
import lava.tracker.nnmclub.feature.NnmclubComments
import lava.tracker.nnmclub.feature.NnmclubDownload
import lava.tracker.nnmclub.feature.NnmclubSearch
import lava.tracker.nnmclub.feature.NnmclubTopic
import lava.tracker.nnmclub.http.NnmclubHttpClient
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain NNM-Club client.
 *
 * Wraps the feature impls that match the capabilities [NnmclubDescriptor] declares
 * and exposes them via [TrackerClient.getFeature].
 */
class NnmclubClient @Inject constructor(
    private val http: NnmclubHttpClient,
    private val search: NnmclubSearch,
    private val browse: NnmclubBrowse,
    private val topic: NnmclubTopic,
    private val comments: NnmclubComments,
    private val auth: NnmclubAuth,
    private val download: NnmclubDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = NnmclubDescriptor

    override suspend fun healthCheck(): Boolean = try {
        http.get("https://nnmclub.to/forum/index.php").use { response ->
            response.isSuccessful && (response.body?.string().orEmpty()).contains(
                descriptor.expectedHealthMarker,
                ignoreCase = true,
            )
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
            CommentsTracker::class -> if (TrackerCapability.COMMENTS in caps) comments as T else null
            AuthenticatableTracker::class -> if (TrackerCapability.AUTH_REQUIRED in caps) auth as T else null
            DownloadableTracker::class -> if (TrackerCapability.TORRENT_DOWNLOAD in caps) download as T else null
            else -> null
        }
    }

    override fun close() {
        // No HTTP resources owned directly here.
    }
}
