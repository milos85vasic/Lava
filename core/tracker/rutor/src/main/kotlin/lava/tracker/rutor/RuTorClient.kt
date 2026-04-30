package lava.tracker.rutor

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
import lava.tracker.rutor.feature.RuTorAuth
import lava.tracker.rutor.feature.RuTorBrowse
import lava.tracker.rutor.feature.RuTorComments
import lava.tracker.rutor.feature.RuTorDownload
import lava.tracker.rutor.feature.RuTorSearch
import lava.tracker.rutor.feature.RuTorTopic
import lava.tracker.rutor.http.RuTorHttpClient
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain RuTor client (SP-3a Task 3.40, Section J).
 *
 * Wraps the 6 feature impls that match the capabilities [RuTorDescriptor]
 * declares (SEARCH, BROWSE, TOPIC, COMMENTS, TORRENT_DOWNLOAD, AUTH_REQUIRED;
 * MAGNET_LINK and RSS are surfaces of TopicTracker / future feeds, not
 * separate features) and exposes them via [TrackerClient.getFeature].
 *
 * Capability Honesty (clause 6.E): every capability declared in
 * [RuTorDescriptor.capabilities] that maps to a [TrackerFeature] interface
 * MUST resolve to a non-null impl here. RuTor declares no FAVORITES (anonymous
 * tracker; no per-user list endpoint) so [FavoritesTracker] always returns
 * null — which the test suite asserts.
 *
 * [healthCheck] performs a fetch of the primary mirror's home page to verify
 * the tracker is reachable. The OkHttp client throws on connect/timeout; we
 * surface that as `false`.
 */
class RuTorClient @Inject constructor(
    private val http: RuTorHttpClient,
    private val search: RuTorSearch,
    private val browse: RuTorBrowse,
    private val topic: RuTorTopic,
    private val comments: RuTorComments,
    private val auth: RuTorAuth,
    private val download: RuTorDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = RuTorDescriptor

    override suspend fun healthCheck(): Boolean = try {
        http.get("https://rutor.info/").use { response ->
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
            // Capability Honesty (clause 6.E): rutor has no FAVORITES surface.
            FavoritesTracker::class -> null
            else -> null
        }
    }

    override fun close() {
        // No HTTP resources owned directly here; RuTorHttpClient is a singleton and
        // its OkHttpClient is closed at process shutdown.
    }
}
