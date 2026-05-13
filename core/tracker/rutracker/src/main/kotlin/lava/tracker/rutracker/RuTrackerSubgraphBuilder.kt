package lava.tracker.rutracker

import io.ktor.client.HttpClient
import lava.auth.api.TokenProvider
import lava.tracker.rutracker.domain.AddCommentUseCase
import lava.tracker.rutracker.domain.AddFavoriteUseCase
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.GetCategoryPageUseCase
import lava.tracker.rutracker.domain.GetCommentsPageUseCase
import lava.tracker.rutracker.domain.GetCurrentProfileUseCase
import lava.tracker.rutracker.domain.GetFavoritesUseCase
import lava.tracker.rutracker.domain.GetForumUseCase
import lava.tracker.rutracker.domain.GetMagnetLinkUseCase
import lava.tracker.rutracker.domain.GetProfileUseCase
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.domain.GetTorrentFileUseCase
import lava.tracker.rutracker.domain.LoginUseCase
import lava.tracker.rutracker.domain.LogoutUseCase
import lava.tracker.rutracker.domain.ParseCommentsPageUseCase
import lava.tracker.rutracker.domain.ParseTorrentUseCase
import lava.tracker.rutracker.domain.RemoveFavoriteUseCase
import lava.tracker.rutracker.domain.VerifyAuthorisedUseCase
import lava.tracker.rutracker.domain.VerifyTokenUseCase
import lava.tracker.rutracker.domain.WithAuthorisedCheckUseCase
import lava.tracker.rutracker.domain.WithFormTokenUseCase
import lava.tracker.rutracker.domain.WithTokenVerificationUseCase
import lava.tracker.rutracker.feature.RuTrackerAuth
import lava.tracker.rutracker.feature.RuTrackerBrowse
import lava.tracker.rutracker.feature.RuTrackerComments
import lava.tracker.rutracker.feature.RuTrackerDownload
import lava.tracker.rutracker.feature.RuTrackerFavorites
import lava.tracker.rutracker.feature.RuTrackerSearch
import lava.tracker.rutracker.feature.RuTrackerTopic
import lava.tracker.rutracker.impl.RuTrackerInnerApiImpl
import lava.tracker.rutracker.mapper.AuthMapper
import lava.tracker.rutracker.mapper.CategoryPageMapper
import lava.tracker.rutracker.mapper.CommentsMapper
import lava.tracker.rutracker.mapper.FavoritesMapper
import lava.tracker.rutracker.mapper.ForumDtoMapper
import lava.tracker.rutracker.mapper.SearchPageMapper
import lava.tracker.rutracker.mapper.TopicMapper

/**
 * SP-4 Phase F.2.6 (2026-05-13) — pure-Kotlin builder that constructs
 * a fully-wired [RuTrackerClient] from a caller-supplied
 * [HttpClient] (the base URL is encoded in the HttpClient's
 * `defaultRequest`).
 *
 * Use case: [RuTrackerClientFactory.create] calls this for a CLONED
 * provider so the clone's `primaryUrl` propagates all the way down
 * to every HTTP call (login → search → browse → topic → comments →
 * download → favorites). The Hilt-singleton path
 * (`@Inject constructor` chain rooted at
 * `:core:tracker:client:di:TrackerClientModule`) remains the original-
 * tracker path; this builder is mechanically equivalent to that
 * chain — adding it doesn't change original-tracker behavior.
 *
 * Why a hand-rolled object and not Dagger: Hilt scopes the original-
 * tracker subgraph as `@Singleton`. Hilt has no support for "build
 * me a fresh subgraph with parameter X" at runtime; we'd need
 * `@AssistedInject` everywhere or a separate non-Hilt sub-component.
 * The simpler answer is a plain factory object that mirrors the
 * Hilt graph 1:1 — verified by visual diff against
 * `TrackerClientModule`.
 */
object RuTrackerSubgraphBuilder {

    fun build(httpClient: HttpClient, tokenProvider: TokenProvider): RuTrackerClient {
        val innerApi = RuTrackerInnerApiImpl(httpClient)

        // Token-verification helpers
        val withToken = WithTokenVerificationUseCase(VerifyTokenUseCase)
        val withAuth = WithAuthorisedCheckUseCase(VerifyAuthorisedUseCase)

        // 14 feature-public use cases (1:1 with TrackerClientModule's @Provides set)
        val getSearchPage = GetSearchPageUseCase(innerApi, withToken, withAuth)
        val getForum = GetForumUseCase(innerApi)
        val getCategoryPage = GetCategoryPageUseCase(innerApi)
        val getTopic = GetTopicUseCase(innerApi, ParseTorrentUseCase, ParseCommentsPageUseCase)
        val getTopicPage = GetTopicPageUseCase(innerApi, ParseTopicPageUseCaseRef)
        val getCommentsPage = GetCommentsPageUseCase(innerApi, ParseCommentsPageUseCase)
        val addComment = AddCommentUseCase(innerApi, withToken, withAuth, WithFormTokenUseCase)
        val getFavorites = GetFavoritesUseCase(innerApi, withToken, withAuth)
        val addFavorite = AddFavoriteUseCase(innerApi, withToken, withAuth, WithFormTokenUseCase)
        val removeFavorite = RemoveFavoriteUseCase(innerApi, withToken, withAuth, WithFormTokenUseCase)
        val getProfile = GetProfileUseCase(innerApi)
        val getCurrentProfile = GetCurrentProfileUseCase(innerApi, getProfile)
        val login = LoginUseCase(innerApi, getCurrentProfile)
        val logout = LogoutUseCase(tokenProvider)
        val checkAuthorised = CheckAuthorisedUseCase(innerApi, VerifyAuthorisedUseCase)
        val getTorrentFile = GetTorrentFileUseCase(innerApi, withToken)
        val getMagnetLink = GetMagnetLinkUseCase()

        // 8 mappers (all @Inject constructor() — no args)
        val searchMapper = SearchPageMapper()
        val categoryMapper = CategoryPageMapper()
        val forumMapper = ForumDtoMapper()
        val topicMapper = TopicMapper()
        val commentsMapper = CommentsMapper()
        val authMapper = AuthMapper()
        val favoritesMapper = FavoritesMapper()

        // 7 feature impls
        val search = RuTrackerSearch(getSearchPage, searchMapper, tokenProvider)
        val browse = RuTrackerBrowse(getCategoryPage, getForum, categoryMapper, forumMapper)
        val topic = RuTrackerTopic(getTopic, getTopicPage, topicMapper, tokenProvider)
        val comments = RuTrackerComments(getCommentsPage, addComment, commentsMapper, tokenProvider)
        val auth = RuTrackerAuth(login, logout, checkAuthorised, authMapper, tokenProvider)
        val download = RuTrackerDownload(getTorrentFile, getMagnetLink, tokenProvider)
        val favorites = RuTrackerFavorites(getFavorites, addFavorite, removeFavorite, favoritesMapper, tokenProvider)

        return RuTrackerClient(
            search = search,
            browse = browse,
            topic = topic,
            comments = comments,
            favorites = favorites,
            auth = auth,
            download = download,
        )
    }

    // The ParseTopicPageUseCase object is referenced indirectly via the
    // same import path TrackerClientModule uses; alias here keeps the
    // single read site close to the build() body for diff-with-Hilt parity.
    private val ParseTopicPageUseCaseRef get() =
        lava.tracker.rutracker.domain.ParseTopicPageUseCase
}
