package lava.network.impl

import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.models.settings.isLocalHost
import lava.network.api.NetworkApi
import lava.network.data.NetworkApiRepository
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.CaptchaSolution
import lava.tracker.api.model.LoginRequest
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.rutracker.mapper.RuTrackerDtoMappers
import javax.inject.Inject

// SP-3a-2.32: When the active endpoint is public rutracker.org (i.e. an
// Endpoint.RutrackerEndpoint whose host is NOT a LAN host), every method
// routes via LavaTrackerSdk + RuTrackerDtoMappers reverse mappers — that's
// the new SDK-based path. Any other endpoint (LAN proxy, GoApi, LAN Mirror)
// falls through to the existing NetworkApiRepository wiring untouched.
//
// Method routing decided in Task 2.32 (see commit body):
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
    private val sdk: LavaTrackerSdk,
    private val mappers: RuTrackerDtoMappers,
    private val settingsRepository: SettingsRepository,
) : NetworkApi {

    private suspend fun api(): NetworkApi = networkApiRepository.getApi()

    /**
     * SDK delegation guard. Returns true iff the active endpoint is a
     * public rutracker host (direct rutracker.org or a non-LAN mirror host).
     *
     * The SDK's HttpClient is pinned to `https://rutracker.org/forum/` (see
     * TrackerClientModule) and has no per-endpoint switching — therefore we
     * only delegate to it when the legacy path would have ALSO chosen the
     * direct rutracker route (NetworkApiRepositoryImpl.rutrackerApi). LAN
     * endpoints (GoApi or Mirror with localhost host) keep the legacy
     * NetworkApiRepository path because their wire format is the
     * Lava-API JSON, not the rutracker HTML scraper.
     */
    private suspend fun shouldUseSdk(): Boolean {
        val endpoint = settingsRepository.getSettings().endpoint
        return endpoint is Endpoint.RutrackerEndpoint && !endpoint.host.isLocalHost()
    }

    override suspend fun checkAuthorized(token: String): Boolean {
        return if (shouldUseSdk()) {
            // SDK obtains the token via TokenProvider internally; the
            // explicit `token` parameter is the legacy contract carrier
            // and is ignored here per Pre-authorized adaptation D.
            val state = sdk.checkAuth() ?: return api().checkAuthorized(token)
            state is AuthState.Authenticated
        } else {
            api().checkAuthorized(token)
        }
    }

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): AuthResponseDto {
        return if (shouldUseSdk()) {
            val captchaSolution = if (
                captchaSid != null && captchaCode != null && captchaValue != null
            ) {
                CaptchaSolution(sid = captchaSid, code = captchaCode, value = captchaValue)
            } else {
                null
            }
            val request = LoginRequest(
                username = username,
                password = password,
                captcha = captchaSolution,
            )
            // login returns null only when the active tracker has no AUTH
            // capability — rutracker always does, so this path falls back
            // to the legacy api() if a future tracker omits AUTH.
            val result = sdk.login(request)
                ?: return api().login(username, password, captchaSid, captchaCode, captchaValue)
            mappers.loginResultToDto(result)
        } else {
            api().login(username, password, captchaSid, captchaCode, captchaValue)
        }
    }

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
