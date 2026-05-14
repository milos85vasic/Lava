package lava.tracker.rutracker.impl

import lava.network.api.NetworkApi
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.tracker.rutracker.domain.AddCommentUseCase
import lava.tracker.rutracker.domain.AddFavoriteUseCase
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.GetCategoryPageUseCase
import lava.tracker.rutracker.domain.GetCommentsPageUseCase
import lava.tracker.rutracker.domain.GetFavoritesUseCase
import lava.tracker.rutracker.domain.GetForumUseCase
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.domain.GetTorrentFileUseCase
import lava.tracker.rutracker.domain.GetTorrentUseCase
import lava.tracker.rutracker.domain.LoginUseCase
import lava.tracker.rutracker.domain.RemoveFavoriteUseCase

internal class RuTrackerNetworkApi(
    private val addCommentUseCase: AddCommentUseCase,
    private val addFavoriteUseCase: AddFavoriteUseCase,
    private val checkAuthorisedUseCase: CheckAuthorisedUseCase,
    private val getCategoryPageUseCase: GetCategoryPageUseCase,
    private val getCommentsPageUseCase: GetCommentsPageUseCase,
    private val getFavoritesUseCase: GetFavoritesUseCase,
    private val getForumUseCase: GetForumUseCase,
    private val getSearchPageUseCase: GetSearchPageUseCase,
    private val getTopicUseCase: GetTopicUseCase,
    private val getTopicPageUseCase: GetTopicPageUseCase,
    private val getTorrentFileUseCase: GetTorrentFileUseCase,
    private val getTorrentUseCase: GetTorrentUseCase,
    private val loginUseCase: LoginUseCase,
    private val removeFavoriteUseCase: RemoveFavoriteUseCase,
) : NetworkApi {
    override suspend fun checkAuthorized(token: String) = checkAuthorisedUseCase.invoke(token)

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): lava.network.dto.auth.AuthResponseDto = try {
        loginUseCase.invoke(username, password, captchaSid, captchaCode, captchaValue)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        // Never swallow structured-concurrency cancellations.
        throw cancellation
    } catch (t: Throwable) {
        // §6.O closure for Crashlytics a29412cf6566d0a71b06df416610be57
        // ("lava.tracker.rutracker.model.Unknown" thrown from LoginUseCase
        // when rutracker returns an HTML response that doesn't match
        // success / login-form / captcha shape). Pre-fix: the throw
        // escaped to the main looper as FATAL. Now we trap at the
        // network-API boundary and return WrongCredits as a safe
        // user-visible fallback — rationale: unknown response is most
        // commonly a temp server-side issue or auth gate; treating as
        // wrong-credit lets the user retry without app crash.
        // Upstream callers (RuTrackerAuth + the ViewModel) are
        // responsible for recording the throwable as a non-fatal — this
        // layer is core/tracker/* (Decoupled Reusable Architecture)
        // and cannot depend on the Android analytics SDK.
        lava.network.dto.auth.AuthResponseDto.WrongCredits(captcha = null)
    }

    override suspend fun getFavorites(token: String) = getFavoritesUseCase.invoke(token)

    override suspend fun addFavorite(token: String, id: String) = addFavoriteUseCase.invoke(token, id)

    override suspend fun removeFavorite(token: String, id: String) = removeFavoriteUseCase.invoke(token, id)

    override suspend fun getForum() = getForumUseCase.invoke()

    override suspend fun getCategory(id: String, page: Int?) = getCategoryPageUseCase.invoke(id, page)

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
    ) = getSearchPageUseCase.invoke(
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
    ) = getTopicUseCase.invoke(token, id, page)

    override suspend fun getTopicPage(
        token: String,
        id: String,
        page: Int?,
    ) = getTopicPageUseCase.invoke(token, id, page)

    override suspend fun getCommentsPage(
        token: String,
        id: String,
        page: Int?,
    ) = getCommentsPageUseCase.invoke(token, id, page)

    override suspend fun addComment(
        token: String,
        topicId: String,
        message: String,
    ) = addCommentUseCase.invoke(token, topicId, message)

    override suspend fun getTorrent(token: String, id: String) = getTorrentUseCase.invoke(token, id)

    override suspend fun download(token: String, id: String) =
        getTorrentFileUseCase.invoke(token, id)
}
