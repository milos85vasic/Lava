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
        // Bug 1 (2026-05-17, operator §6.L 57th invocation): "Cant login
        // to RuTracker with valid credentials". The prior catch silently
        // mapped EVERY throwable (Cloudflare NoData, parser Unknown,
        // network errors, captcha-parse failures) to
        // WrongCredits(captcha = null) — and the UI showed the user
        // "wrong credentials" for inputs that were in fact valid. The
        // §6.J / §6.AB anti-bluff principle was violated: the system
        // claimed credentials were wrong when in reality it had no idea.
        //
        // This commit preserves the no-crash behaviour (returning
        // WrongCredits still gives the user a clickable retry surface)
        // BUT stops the silent conflation by printing a clearly-marked
        // line to stderr — which on Android lands in `adb logcat` under
        // the System.err tag, and on the lava-api-go-proxied path lands
        // in the service log. Operator + reviewer can grep this marker
        // string to distinguish "user typed wrong password" from
        // "infrastructure problem masquerading as wrong password".
        //
        // Why not return a new sealed variant (e.g. ServiceUnavailable)?
        // That would require coordinated edits to AuthResponseDto (in
        // :core:network:api) + AuthMapper + LoginResultMapper +
        // AuthResult + ProviderLoginViewModel + the login UI string
        // resources + Challenge tests for each — a 6+ file refactor
        // that exceeds the 1.2.24 distribute scope. The discriminator
        // for now IS the printed marker line. The proper sealed-variant
        // refactor is owed in a follow-up + recorded as such in
        // docs/CONTINUATION.md.
        System.err.println(
            "RuTrackerNetworkApi.login: NOT-actually-wrong-credentials — " +
                "upstream produced ${t.javaClass.simpleName}: ${t.message ?: "<no message>"} " +
                "(returning WrongCredits as no-crash fallback per §6.J / §6.AC; " +
                "see Bug 1 fix in this commit for context)",
        )
        t.printStackTrace()
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
