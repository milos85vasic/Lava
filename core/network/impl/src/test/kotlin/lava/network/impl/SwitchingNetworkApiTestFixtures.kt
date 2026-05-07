package lava.network.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.network.api.NetworkApi
import lava.network.data.NetworkApiRepository
import lava.network.dto.FileDto
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.UserDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicPageCommentsDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDataDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.user.FavoritesDto

/**
 * Anti-Bluff (Third Law) test scaffolding for [SwitchingNetworkApi].
 *
 * Lives in `src/test` so that production code never imports it. Fakes here
 * intentionally MIRROR real-impl behaviour:
 *   - [FakeSettingsRepository] persists [Settings.endpoint] across calls
 *     just like the real Room-backed repo (no synthetic immutability).
 *   - [FakeLegacyNetworkApi] records its method invocations + arguments and
 *     fails loudly when an SDK-routed test accidentally falls through —
 *     because that would mean the SwitchingNetworkApi guard is broken
 *     (clause 6.6.2 falsifiability anchor).
 */

class FakeSettingsRepository(initial: Endpoint = Endpoint.Rutracker) : SettingsRepository {
    private var current: Settings = Settings(endpoint = initial)

    fun setEndpointSync(endpoint: Endpoint) {
        current = current.copy(endpoint = endpoint)
    }

    override suspend fun getSettings(): Settings = current
    override fun observeSettings(): Flow<Settings> = flowOf(current)
    override suspend fun setTheme(theme: Theme) {
        current = current.copy(theme = theme)
    }

    override suspend fun setEndpoint(endpoint: Endpoint) {
        current = current.copy(endpoint = endpoint)
    }

    override suspend fun setFavoritesSyncPeriod(syncPeriod: SyncPeriod) {
        current = current.copy(favoritesSyncPeriod = syncPeriod)
    }

    override suspend fun setBookmarksSyncPeriod(syncPeriod: SyncPeriod) {
        current = current.copy(bookmarksSyncPeriod = syncPeriod)
    }

    override suspend fun setHistorySyncPeriod(syncPeriod: SyncPeriod) {
        current = current.copy(historySyncPeriod = syncPeriod)
    }

    override suspend fun setCredentialsSyncPeriod(syncPeriod: SyncPeriod) {
        current = current.copy(credentialsSyncPeriod = syncPeriod)
    }
}

/**
 * Records each method invocation. Tests assert on the legacy `calls` log
 * to confirm the SDK guard correctly rejects (the SDK path leaves
 * `calls` empty) or permits (legacy path appends to `calls`) delegation.
 *
 * Stub return values are constructed to be DETECTABLE — if a test sees
 * "legacy-marker" in any user-visible field, the SDK guard misfired.
 */
class FakeLegacyNetworkApi : NetworkApi {
    val calls = mutableListOf<String>()

    var checkAuthorizedReturn: Boolean = false
    var loginReturn: AuthResponseDto = AuthResponseDto.Success(
        user = UserDto(id = "legacy-user-id", token = "legacy-marker-token", avatarUrl = ""),
    )
    var favoritesReturn: FavoritesDto = FavoritesDto(topics = emptyList())
    var addFavoriteReturn: Boolean = true
    var removeFavoriteReturn: Boolean = true
    var forumReturn: ForumDto = ForumDto(children = emptyList())
    var categoryReturn: CategoryPageDto = CategoryPageDto(
        category = lava.network.dto.forum.CategoryDto(id = "legacy-cat", name = "legacy-marker"),
        page = 1,
        pages = 1,
        sections = null,
        children = null,
        topics = emptyList(),
    )
    var searchReturn: SearchPageDto = SearchPageDto(page = 1, pages = 1, torrents = emptyList())
    var topicReturn: ForumTopicDto = TorrentDto(
        id = "legacy-marker",
        title = "legacy-marker-topic",
        author = null,
        category = null,
    )
    var topicPageReturn: TopicPageDto = TopicPageDto(
        id = "legacy-marker",
        title = "legacy-marker-topic-page",
        author = null,
        category = null,
        torrentData = TorrentDataDto(
            tags = null,
            posterUrl = null,
            status = null,
            date = null,
            size = null,
            seeds = null,
            leeches = null,
            magnetLink = null,
        ),
        commentsPage = TopicPageCommentsDto(page = 1, pages = 1, posts = emptyList()),
    )
    var commentsPageReturn: CommentsPageDto = CommentsPageDto(
        id = "legacy-marker",
        title = "legacy-marker-comments",
        page = 1,
        pages = 1,
        posts = emptyList(),
    )
    var addCommentReturn: Boolean = true
    var torrentReturn: lava.network.dto.topic.TorrentDto = TorrentDto(
        id = "legacy-marker",
        title = "legacy-marker-torrent",
        author = null,
        category = null,
    )
    var downloadReturn: FileDto = FileDto(
        contentDisposition = "attachment; filename=legacy.torrent",
        contentType = "application/x-bittorrent",
        bytes = "legacy-marker-bytes".toByteArray(),
    )

    override suspend fun checkAuthorized(token: String): Boolean {
        calls += "checkAuthorized($token)"
        return checkAuthorizedReturn
    }

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): AuthResponseDto {
        calls += "login($username,$password,$captchaSid,$captchaCode,$captchaValue)"
        return loginReturn
    }

    override suspend fun getFavorites(token: String): FavoritesDto {
        calls += "getFavorites($token)"
        return favoritesReturn
    }

    override suspend fun addFavorite(token: String, id: String): Boolean {
        calls += "addFavorite($token,$id)"
        return addFavoriteReturn
    }

    override suspend fun removeFavorite(token: String, id: String): Boolean {
        calls += "removeFavorite($token,$id)"
        return removeFavoriteReturn
    }

    override suspend fun getForum(): ForumDto {
        calls += "getForum"
        return forumReturn
    }

    override suspend fun getCategory(id: String, page: Int?): CategoryPageDto {
        calls += "getCategory($id,$page)"
        return categoryReturn
    }

    override suspend fun getSearchPage(
        token: String,
        searchQuery: String?,
        categories: String?,
        author: String?,
        authorId: String?,
        sortType: SearchSortTypeDto?,
        sortOrder: SearchSortOrderDto?,
        period: SearchPeriodDto?,
        page: Int?,
    ): SearchPageDto {
        calls += "getSearchPage($searchQuery,$categories,$author,$authorId,$sortType,$sortOrder,$period,$page)"
        return searchReturn
    }

    override suspend fun getTopic(token: String, id: String, page: Int?): ForumTopicDto {
        calls += "getTopic($token,$id,$page)"
        return topicReturn
    }

    override suspend fun getTopicPage(token: String, id: String, page: Int?): TopicPageDto {
        calls += "getTopicPage($token,$id,$page)"
        return topicPageReturn
    }

    override suspend fun getCommentsPage(token: String, id: String, page: Int?): CommentsPageDto {
        calls += "getCommentsPage($token,$id,$page)"
        return commentsPageReturn
    }

    override suspend fun addComment(token: String, topicId: String, message: String): Boolean {
        calls += "addComment($token,$topicId,$message)"
        return addCommentReturn
    }

    override suspend fun getTorrent(token: String, id: String): TorrentDto {
        calls += "getTorrent($token,$id)"
        return torrentReturn
    }

    override suspend fun download(token: String, id: String): FileDto {
        calls += "download($token,$id)"
        return downloadReturn
    }
}

class FakeNetworkApiRepository(private val api: NetworkApi) : NetworkApiRepository {
    override suspend fun getApi(): NetworkApi = api
    override suspend fun getCaptchaUrl(url: String): String = "captcha:$url"
    override suspend fun getDownloadUri(id: String): String = "download:$id"
    override suspend fun getAuthHeader(token: String): Pair<String, String> = "Cookie" to token
}
