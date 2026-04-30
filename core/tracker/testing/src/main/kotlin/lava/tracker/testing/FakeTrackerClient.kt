package lava.tracker.testing

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
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.CommentsPage
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentItem
import kotlin.reflect.KClass

class FakeTrackerClient(override val descriptor: TrackerDescriptor) : TrackerClient {

    var healthy: Boolean = true
    var searchResultProvider: (SearchRequest, Int) -> SearchResult =
        { _, _ -> SearchResult(emptyList(), 0, 0) }
    var browseResultProvider: (String?, Int) -> BrowseResult =
        { _, _ -> BrowseResult(emptyList(), 0, 0) }
    var topicProvider: ((String) -> TopicDetail)? = null
    var loginProvider: (LoginRequest) -> LoginResult = { LoginResult(AuthState.Unauthenticated) }
    var downloadProvider: ((String) -> ByteArray)? = null

    override suspend fun healthCheck() = healthy

    @Suppress("UNCHECKED_CAST")
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? {
        // Constitutional clause 6.E: return non-null only for declared capabilities.
        val declared = descriptor.capabilities
        return when (featureClass) {
            SearchableTracker::class -> if (TrackerCapability.SEARCH in declared) (search as T) else null
            BrowsableTracker::class -> if (TrackerCapability.BROWSE in declared) (browse as T) else null
            TopicTracker::class -> if (TrackerCapability.TOPIC in declared) (topic as T) else null
            CommentsTracker::class -> if (TrackerCapability.COMMENTS in declared) (comments as T) else null
            FavoritesTracker::class -> if (TrackerCapability.FAVORITES in declared) (favorites as T) else null
            AuthenticatableTracker::class -> if (TrackerCapability.AUTH_REQUIRED in declared) (auth as T) else null
            DownloadableTracker::class -> if (TrackerCapability.TORRENT_DOWNLOAD in declared) (download as T) else null
            else -> null
        }
    }

    override fun close() {}

    private val search = object : SearchableTracker {
        override suspend fun search(request: SearchRequest, page: Int) = searchResultProvider(request, page)
    }
    private val browse = object : BrowsableTracker {
        override suspend fun browse(category: String?, page: Int) = browseResultProvider(category, page)
        override suspend fun getForumTree() = null
    }
    private val topic = object : TopicTracker {
        override suspend fun getTopic(id: String) = topicProvider?.invoke(id)
            ?: error("FakeTrackerClient.topicProvider not configured for id=$id")

        override suspend fun getTopicPage(id: String, page: Int) =
            TopicPage(getTopic(id), totalPages = 1, currentPage = page)
    }
    private val comments = object : CommentsTracker {
        override suspend fun getComments(topicId: String, page: Int) = CommentsPage(emptyList(), 0, 0)
        override suspend fun addComment(topicId: String, message: String) = true
    }
    private val favorites = object : FavoritesTracker {
        private val store = mutableSetOf<String>()
        override suspend fun list() = store.map {
            TorrentItem(trackerId = descriptor.trackerId, torrentId = it, title = "fav-$it")
        }

        override suspend fun add(id: String) = store.add(id)
        override suspend fun remove(id: String) = store.remove(id)
    }
    private val auth = object : AuthenticatableTracker {
        private var state: AuthState = AuthState.Unauthenticated
        override suspend fun login(req: LoginRequest) = loginProvider(req).also { state = it.state }
        override suspend fun logout() {
            state = AuthState.Unauthenticated
        }

        override suspend fun checkAuth() = state
    }
    private val download = object : DownloadableTracker {
        override suspend fun downloadTorrentFile(id: String) =
            downloadProvider?.invoke(id) ?: ByteArray(0)

        override fun getMagnetLink(id: String) = "magnet:?xt=urn:btih:fakeinfohash$id"
    }
}
