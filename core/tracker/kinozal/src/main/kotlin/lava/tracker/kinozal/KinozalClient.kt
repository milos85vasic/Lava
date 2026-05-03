package lava.tracker.kinozal

import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.kinozal.feature.KinozalAuth
import lava.tracker.kinozal.feature.KinozalBrowse
import lava.tracker.kinozal.feature.KinozalDownload
import lava.tracker.kinozal.feature.KinozalSearch
import lava.tracker.kinozal.feature.KinozalTopic
import lava.tracker.kinozal.http.KinozalHttpClient
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain Kinozal client.
 *
 * Wraps the feature impls that match the capabilities declared in
 * [KinozalDescriptor] and exposes them via [TrackerClient.getFeature].
 */
class KinozalClient @Inject constructor(
    private val http: KinozalHttpClient,
    private val search: KinozalSearch,
    private val browse: KinozalBrowse,
    private val topic: KinozalTopic,
    private val auth: KinozalAuth,
    private val download: KinozalDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = KinozalDescriptor

    override suspend fun healthCheck(): Boolean = try {
        http.get("https://kinozal.tv/").use { response ->
            response.isSuccessful && (http.bodyString(response)).contains(
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
            AuthenticatableTracker::class -> if (TrackerCapability.AUTH_REQUIRED in caps) auth as T else null
            DownloadableTracker::class -> if (TrackerCapability.TORRENT_DOWNLOAD in caps) download as T else null
            // Capability Honesty: Kinozal does not expose COMMENTS or FAVORITES as separate features today.
            CommentsTracker::class -> null
            FavoritesTracker::class -> null
            else -> null
        }
    }

    override fun close() {
        // No-op — KinozalHttpClient is a singleton.
    }
}
