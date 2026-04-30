package lava.network.impl

import lava.network.api.NetworkApi
import lava.network.data.NetworkApiRepository
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import javax.inject.Inject

// SP-3a-2.32: When the active endpoint is public rutracker.org (i.e. an
// Endpoint.RutrackerEndpoint whose host is NOT a LAN host), every method
// routes via LavaTrackerSdk + RuTrackerDtoMappers reverse mappers — that's
// the new SDK-based path. Any other endpoint (LAN proxy, GoApi, LAN Mirror)
// falls through to the existing NetworkApiRepository wiring untouched.
//
// Method routing decided in Task 2.32:
//   checkAuthorized -> sdk.checkAuth() -> AuthState.Authenticated boolean
//   login           -> sdk.login(LoginRequest) -> mappers.loginResultToDto
//   getFavorites    -> sdk.getFavorites() -> mappers.favoritesToDto
//   addFavorite     -> sdk.addFavorite(id): Boolean
//   removeFavorite  -> sdk.removeFavorite(id): Boolean
//   getForum        -> sdk.getForumTree() -> mappers.forumTreeToDto
//   getCategory     -> sdk.browse(category, page) -> mappers.browseResultToDto
//   getSearchPage   -> sdk.search(SearchRequest, page) -> mappers.searchResultToDto
//   getTopic        -> sdk.getTopic(id) -> mappers.topicDetailToDto, cast to TorrentDto
//   getTopicPage    -> sdk.getTopicPage(id, page) -> mappers.topicPageToDto
//   getCommentsPage -> sdk.getCommentsPage(id, page) -> mappers.commentsPageToDto
//   addComment      -> sdk.addComment(topicId, message): Boolean
//   getTorrent      -> sdk.getTopic(id) -> mappers.topicDetailToDto, cast to TorrentDto
//   download        -> sdk.downloadTorrent(id): ByteArray? -> wrapped in synthesised FileDto
//
// The token parameter on the legacy methods is ignored on the SDK path —
// the SDK obtains tokens via TokenProvider internally (Pre-authorized
// adaptation D, Section G). Captcha solution arguments on `login` are
// remapped into [LoginRequest.captcha] before crossing the SDK seam.
//
// `getActiveClient()` was NOT bumped to public — instead [LavaTrackerSdk]
// gained 11 new method wrappers in Task 2.32 covering every NetworkApi
// operation (Pre-authorized adaptation B). See LavaTrackerSdk KDoc.

class SwitchingNetworkApi @Inject constructor(
    private val networkApiRepository: NetworkApiRepository,
) : NetworkApi {

    private suspend fun api(): NetworkApi = networkApiRepository.getApi()

    override suspend fun checkAuthorized(token: String) =
        api().checkAuthorized(token)

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ) = api().login(username, password, captchaSid, captchaCode, captchaValue)

    override suspend fun getFavorites(token: String) =
        api().getFavorites(token)

    override suspend fun addFavorite(token: String, id: String) =
        api().addFavorite(token, id)

    override suspend fun removeFavorite(token: String, id: String) =
        api().removeFavorite(token, id)

    override suspend fun getForum() =
        api().getForum()

    override suspend fun getCategory(id: String, page: Int?) =
        api().getCategory(id, page)

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
    ) = api().getSearchPage(
        token,
        searchQuery,
        categories,
        author,
        authorId,
        sortType,
        sortOrder,
        period,
        page,
    )

    override suspend fun getTopic(
        token: String,
        id: String,
        page: Int?,
    ) = api().getTopic(token, id, page)

    override suspend fun getTopicPage(
        token: String,
        id: String,
        page: Int?,
    ) = api().getTopicPage(token, id, page)

    override suspend fun getCommentsPage(
        token: String,
        id: String,
        page: Int?,
    ) = api().getCommentsPage(token, id, page)

    override suspend fun addComment(token: String, topicId: String, message: String) =
        api().addComment(token, topicId, message)

    override suspend fun getTorrent(token: String, id: String) =
        api().getTorrent(token, id)

    override suspend fun download(token: String, id: String) =
        api().download(token, id)
}
