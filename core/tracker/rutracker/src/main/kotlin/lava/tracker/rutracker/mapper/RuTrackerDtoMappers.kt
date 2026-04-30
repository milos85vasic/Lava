package lava.tracker.rutracker.mapper

import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.user.FavoritesDto
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.CommentsPage
import lava.tracker.api.model.ForumTree
import lava.tracker.api.model.LoginResult
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Inverse of the forward mappers in this package
 * (SearchPageMapper / CategoryPageMapper / ForumDtoMapper / TopicMapper /
 * CommentsMapper / FavoritesMapper / AuthMapper).
 *
 * Used by [SwitchingNetworkApi] (Section G) to translate new SDK results
 * back into legacy DTOs that feature ViewModels still expect. This is a
 * temporary bridge: the SDK is the source of truth from Section G onward,
 * but `feature/` modules still consume the `:core:network:api` DTO
 * surface. When Spec 2 migrates `feature/` to consume [LavaTrackerSdk]
 * directly, this
 * aggregator and the reverse mappers it composes are deleted.
 *
 * Round-trip equivalence guarantee:
 *   `forward(reverse(forward(dto))) == forward(dto)`
 * which is the strongest equivalence achievable given the legitimate
 * information loss documented per-mapper (formatted size strings, rich
 * post AST, UserDto.id/avatarUrl, etc.). The strict bidirectional
 * `reverse(forward(dto)) == dto` is NOT guaranteed because forward
 * mapping deliberately drops some DTO data (root-only metadata, post
 * tree structure, `UserDto` profile fields).
 *
 * Note on [LegacySearchParams]: Section E does NOT reverse the
 * `SearchRequest -> LegacySearchParams` direction. Both
 * `SortField.RELEVANCE -> SearchSortTypeDto.Date` and
 * `TimePeriod.LAST_YEAR -> SearchPeriodDto.AllTime` are forward-only
 * collapses (multiple model values map to the same DTO value), which
 * means there is no canonical inverse. Reversing search-page results
 * (`SearchResult -> SearchPageDto`) is well-defined and is what this
 * aggregator implements.
 */
class RuTrackerDtoMappers @Inject constructor() {

    fun searchResultToDto(result: SearchResult): SearchPageDto = TODO("Task 2.23")

    fun browseResultToDto(result: BrowseResult): CategoryPageDto = TODO("Task 2.24")

    fun forumTreeToDto(tree: ForumTree): ForumDto = TODO("Task 2.25")

    fun topicDetailToDto(d: TopicDetail): ForumTopicDto = TODO("Task 2.26")

    fun topicPageToDto(p: TopicPage): TopicPageDto = TODO("Task 2.26")

    fun commentsPageToDto(p: CommentsPage): CommentsPageDto = TODO("Task 2.26")

    fun favoritesToDto(items: List<TorrentItem>): FavoritesDto = TODO("Task 2.27")

    fun loginResultToDto(r: LoginResult): AuthResponseDto = TODO("Task 2.27")
}
