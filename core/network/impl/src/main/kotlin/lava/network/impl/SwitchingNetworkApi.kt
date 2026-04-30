package lava.network.impl

import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.models.settings.isLocalHost
import lava.network.api.NetworkApi
import lava.network.data.NetworkApiRepository
import lava.network.dto.FileDto
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.TorrentDto
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.CaptchaSolution
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SortField
import lava.tracker.api.model.SortOrder
import lava.tracker.api.model.TimePeriod
import lava.tracker.client.BrowseOutcome
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.client.SearchOutcome
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
        if (shouldUseSdk()) {
            // SDK obtains the auth token internally via TokenProvider.
            val items = sdk.getFavorites()
                ?: return api().getFavorites(token)
            mappers.favoritesToDto(items)
        } else {
            api().getFavorites(token)
        }

    override suspend fun addFavorite(token: String, id: String) =
        if (shouldUseSdk()) {
            sdk.addFavorite(id)
        } else {
            api().addFavorite(token, id)
        }

    override suspend fun removeFavorite(token: String, id: String) =
        if (shouldUseSdk()) {
            sdk.removeFavorite(id)
        } else {
            api().removeFavorite(token, id)
        }

    override suspend fun getForum() =
        if (shouldUseSdk()) {
            val tree = sdk.getForumTree()
                ?: return api().getForum()
            mappers.forumTreeToDto(tree)
        } else {
            api().getForum()
        }

    override suspend fun getCategory(id: String, page: Int?) =
        if (shouldUseSdk()) {
            // BrowsableTracker.browse uses 0-based pagination by default
            // (page: Int = 0); legacy NetworkApi accepts a nullable Int. Map
            // null -> 0 to match the SDK default.
            val outcome = sdk.browse(category = id, page = page ?: 0)
            when (outcome) {
                is BrowseOutcome.Success -> mappers.browseResultToDto(outcome.result)
                is BrowseOutcome.Failure -> throw IllegalStateException(
                    outcome.reason,
                    outcome.cause,
                )
                // SP-3a Phase 4 (Task 4.7): legacy NetworkApi has no surface
                // for cross-tracker fallback proposals. Surface the failure
                // identically to Failure for now; the new SDK callers
                // (e.g. :feature:tracker_settings) handle the proposal
                // directly.
                is BrowseOutcome.CrossTrackerFallbackProposed ->
                    throw IllegalStateException("All ${outcome.failedTrackerId} mirrors unavailable")
            }
        } else {
            api().getCategory(id, page)
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
    ) = if (shouldUseSdk()) {
        // SP-3a-2.34 parity NOTE: the legacy SearchSortTypeDto carries
        // `Downloaded` and the legacy SearchPeriodDto carries
        // `LastThreeDays` / `LastTwoWeeks`. Neither has a one-to-one
        // mapping in [SortField] / [TimePeriod] respectively, so a literal
        // legacy→model→legacy round trip via the SDK is provably lossy
        // for those values. Section H is the parity gate that decides
        // whether to widen the model or keep these on the legacy path.
        // For now we route via SDK with the documented collapses (the
        // forward mapper [toLegacySearchParams] applies the canonical
        // collapse — Downloaded silently degrades to Date, LastThreeDays
        // to LastWeek, LastTwoWeeks to LastMonth — see SearchPageMapper
        // KDoc for details).
        val request = SearchRequest(
            query = searchQuery.orEmpty(),
            categories = categories?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            sort = sortType.toSortField(),
            sortOrder = sortOrder.toSortOrder(),
            author = author,
            period = period.toTimePeriod(),
        )
        val outcome = sdk.search(request, page = page ?: 0)
        when (outcome) {
            is SearchOutcome.Success -> mappers.searchResultToDto(outcome.result)
            is SearchOutcome.Failure -> throw IllegalStateException(
                outcome.reason,
                outcome.cause,
            )
            is SearchOutcome.CrossTrackerFallbackProposed ->
                throw IllegalStateException("All ${outcome.failedTrackerId} mirrors unavailable")
        }
    } else {
        api().getSearchPage(
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
    }

    override suspend fun getTopic(
        token: String,
        id: String,
        page: Int?,
    ) = if (shouldUseSdk()) {
        // Legacy `page` argument is unused on this code path because the
        // historical `GetTopicUseCase` ignored it as well — fetching the
        // first page of the topic is the only operation the rutracker
        // scraper supports under this endpoint name. The SDK facade
        // matches that behaviour (suspending TopicTracker.getTopic(id)).
        val detail = sdk.getTopic(id)
            ?: return api().getTopic(token, id, page)
        mappers.topicDetailToDto(detail)
    } else {
        api().getTopic(token, id, page)
    }

    override suspend fun getTopicPage(
        token: String,
        id: String,
        page: Int?,
    ) = if (shouldUseSdk()) {
        val topicPage = sdk.getTopicPage(id, page = page ?: 0)
            ?: return api().getTopicPage(token, id, page)
        mappers.topicPageToDto(topicPage)
    } else {
        api().getTopicPage(token, id, page)
    }

    override suspend fun getCommentsPage(
        token: String,
        id: String,
        page: Int?,
    ) = if (shouldUseSdk()) {
        val comments = sdk.getCommentsPage(id, page = page ?: 0)
            ?: return api().getCommentsPage(token, id, page)
        mappers.commentsPageToDto(comments)
    } else {
        api().getCommentsPage(token, id, page)
    }

    override suspend fun addComment(token: String, topicId: String, message: String) =
        if (shouldUseSdk()) {
            sdk.addComment(topicId, message)
        } else {
            api().addComment(token, topicId, message)
        }

    override suspend fun getTorrent(token: String, id: String): TorrentDto {
        if (shouldUseSdk()) {
            val detail = sdk.getTopic(id)
                ?: return api().getTorrent(token, id)
            // [topicDetailToDto] returns a sealed [ForumTopicDto] (Torrent /
            // Topic / CommentsPage). The legacy NetworkApi.getTorrent
            // signature requires a [TorrentDto]. The rutracker scraper for
            // topic.php always emits a TorrentDto on the success path —
            // which the reverse mapper preserves on the default branch.
            // If the SDK round-trip somehow yields a non-Torrent shape
            // (e.g. metadata["rutracker.kind"] == "topic" or "comments"),
            // we fall back to the legacy api() rather than ClassCastException
            // out, because the legacy contract guarantees TorrentDto.
            return when (val dto = mappers.topicDetailToDto(detail)) {
                is TorrentDto -> dto
                else -> api().getTorrent(token, id)
            }
        }
        return api().getTorrent(token, id)
    }

    override suspend fun download(token: String, id: String): FileDto {
        if (shouldUseSdk()) {
            val bytes = sdk.downloadTorrent(id)
                ?: return api().download(token, id)
            // SP-3a-2.35 parity NOTE: The SDK download facade returns
            // ByteArray only — the legacy FileDto carries
            // contentDisposition + contentType too. Those headers are
            // server-supplied diagnostics and ARE NOT consumed anywhere
            // in the Android client (only the proxy fat JAR uses them
            // server-side). We synthesise the standard rutracker
            // attachment header pair so the FileDto round-trip is shaped
            // correctly for any future downstream consumer; if a
            // user-visible regression surfaces from this, expanding the
            // SDK facade with a proper FileOutcome / FileResult is the
            // right fix.
            return FileDto(
                contentDisposition = "attachment; filename=$id.torrent",
                contentType = "application/x-bittorrent",
                bytes = bytes,
            )
        }
        return api().download(token, id)
    }
}

/**
 * Forward-direction inverse of [lava.tracker.rutracker.mapper.toLegacySearchParams]
 * for the legacy → model leg. The reverse mapper is documented as
 * one-way-collapse-aware:
 *  - `SearchSortTypeDto.Downloaded` → `SortField.DATE` (no `DOWNLOADED`
 *    enumerator on SortField; collapses to default per Section E note).
 *  - `null` → `SortField.DATE` (Lava UI's default search sort).
 */
private fun SearchSortTypeDto?.toSortField(): SortField = when (this) {
    SearchSortTypeDto.Date, null -> SortField.DATE
    SearchSortTypeDto.Title -> SortField.TITLE
    SearchSortTypeDto.Downloaded -> SortField.DATE
    SearchSortTypeDto.Seeds -> SortField.SEEDERS
    SearchSortTypeDto.Leeches -> SortField.LEECHERS
    SearchSortTypeDto.Size -> SortField.SIZE
}

private fun SearchSortOrderDto?.toSortOrder(): SortOrder = when (this) {
    SearchSortOrderDto.Ascending -> SortOrder.ASCENDING
    SearchSortOrderDto.Descending, null -> SortOrder.DESCENDING
}

/**
 * `SearchPeriodDto.LastThreeDays` and `LastTwoWeeks` have no exact
 * counterparts in [TimePeriod]; we map them to the next-coarser bucket
 * (LAST_WEEK / LAST_MONTH respectively) so the search still returns
 * relevant results rather than failing. The reverse `toLegacySearchParams`
 * collapses LAST_YEAR to AllTime — see Section E `RuTrackerDtoMappers` KDoc
 * for the documented information loss.
 */
private fun SearchPeriodDto?.toTimePeriod(): TimePeriod? = when (this) {
    null -> null
    SearchPeriodDto.AllTime -> TimePeriod.ALL_TIME
    SearchPeriodDto.Today -> TimePeriod.LAST_DAY
    SearchPeriodDto.LastThreeDays -> TimePeriod.LAST_WEEK
    SearchPeriodDto.LastWeek -> TimePeriod.LAST_WEEK
    SearchPeriodDto.LastTwoWeeks -> TimePeriod.LAST_MONTH
    SearchPeriodDto.LastMonth -> TimePeriod.LAST_MONTH
}
