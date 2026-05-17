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
        // Bug 1 FULL FIX (2026-05-17, operator §6.L 57th invocation
        // follow-up): "Cant login to RuTracker with valid credentials".
        //
        // The partial fix in commit 17ceabcb mapped every catch to
        // WrongCredits(null) + printed a stderr marker — the marker
        // helped operator triage but the UI still rendered "Wrong
        // credentials" to the user, which is the §6.J bluff the
        // operator's restated mandate forbids ("tests pass while the
        // feature is broken for end users").
        //
        // This full fix introduces a NEW sealed variant
        // `AuthResponseDto.ServiceUnavailable(reason, captcha)` that
        // propagates through:
        //   AuthMapper          → AuthState.ServiceUnavailable(reason)
        //   RuTrackerDtoMappers → reverse mapper for the same
        //   AuthServiceImpl     → AuthResult.ServiceUnavailable(reason)
        //   LoginUseCase        → passes through unchanged
        //   LoginResultMapper   → maps to AuthResult.ServiceUnavailable
        //   ProviderLoginVM     → ProviderLoginState.serviceUnavailable
        //                          + recordWarning telemetry (§6.AC)
        //   ProviderLoginScreen → renders the user-visible "Service
        //                          unavailable. Please try again later."
        //                          string with the reason appended
        //
        // We KEEP the stderr marker (defense-in-depth — the operator's
        // adb logcat trail still surfaces every fired catch even on
        // builds where the UI might not yet have caught up to the new
        // state field), but the load-bearing user-visible behavior is
        // now the structured ServiceUnavailable state rather than the
        // bluffed WrongCredits.
        System.err.println(
            "RuTrackerNetworkApi.login: NOT-actually-wrong-credentials — " +
                "upstream produced ${t.javaClass.simpleName}: ${t.message ?: "<no message>"} " +
                "(returning ServiceUnavailable per §6.J anti-bluff; reason will reach UI)",
        )
        t.printStackTrace()
        lava.network.dto.auth.AuthResponseDto.ServiceUnavailable(
            reason = "${t.javaClass.simpleName}: ${t.message ?: "<no message>"}",
        )
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
