package lava.tracker.rutracker.mapper

import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import lava.network.dto.user.FavoritesDto
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.CommentsPage
import lava.tracker.api.model.ForumCategory
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

    fun searchResultToDto(result: SearchResult): SearchPageDto = SearchPageDto(
        page = result.currentPage,
        pages = result.totalPages,
        torrents = result.items.map { it.toTorrentDto() },
    )

    fun browseResultToDto(result: BrowseResult): CategoryPageDto {
        val topics: List<ForumTopicDto> = result.items.map { it.toForumTopicDto() }
        val category = result.category?.toCategoryDto() ?: CategoryDto(id = null, name = "")
        return CategoryPageDto(
            category = category,
            page = result.currentPage,
            pages = result.totalPages,
            sections = null,
            children = category.children,
            topics = topics,
        )
    }

    fun forumTreeToDto(tree: ForumTree): ForumDto =
        ForumDto(children = tree.rootCategories.map { it.toCategoryDto() })

    fun topicDetailToDto(d: TopicDetail): ForumTopicDto = TODO("Task 2.26")

    fun topicPageToDto(p: TopicPage): TopicPageDto = TODO("Task 2.26")

    fun commentsPageToDto(p: CommentsPage): CommentsPageDto = TODO("Task 2.26")

    fun favoritesToDto(items: List<TorrentItem>): FavoritesDto = TODO("Task 2.27")

    fun loginResultToDto(r: LoginResult): AuthResponseDto = TODO("Task 2.27")
}

/**
 * Reverse of [SearchPageMapper.toTorrentItem] / [TopicDto.toTorrentItem].
 *
 * Reads metadata keys "rutracker.categoryId", "rutracker.categoryName",
 * "rutracker.authorId", "rutracker.tags", "rutracker.status",
 * "rutracker.size_text" plus the typed fields the forward mapper carried
 * directly (seeders -> seeds, magnetUri -> magnetLink, publishDate epoch
 * seconds -> date).
 *
 * Field synthesis:
 *  - [AuthorDto.name] is required (non-null) by the legacy DTO but
 *    forward mapping never preserves it. We fall back to the metadata
 *    "rutracker.authorId" if present, else empty string. The presence
 *    of an "rutracker.authorId" key is the signal that an author existed
 *    on the forward DTO; without it we omit the AuthorDto entirely.
 *  - [TorrentDto.description] is not round-trippable from this mapper
 *    (descriptions live on TopicDetail, not TorrentItem). Always null.
 *  - [TorrentStatusDto] is restored by name; an unrecognised string maps
 *    to null (graceful) rather than throwing.
 */
private fun TorrentItem.toTorrentDto(): TorrentDto {
    val item = this
    val categoryId = item.metadata["rutracker.categoryId"]
    val categoryName = item.metadata["rutracker.categoryName"] ?: item.category
    val category: CategoryDto? = if (categoryId != null || categoryName != null) {
        CategoryDto(id = categoryId, name = categoryName.orEmpty())
    } else {
        null
    }
    val authorId = item.metadata["rutracker.authorId"]
    val author: AuthorDto? = authorId?.let { AuthorDto(id = it, name = it) }
    val status = item.metadata["rutracker.status"]?.let { name ->
        runCatching { TorrentStatusDto.valueOf(name) }.getOrNull()
    }
    return TorrentDto(
        id = item.torrentId,
        title = item.title,
        author = author,
        category = category,
        tags = item.metadata["rutracker.tags"],
        status = status,
        date = item.publishDate?.epochSeconds,
        size = item.metadata["rutracker.size_text"],
        seeds = item.seeders,
        leeches = item.leechers,
        magnetLink = item.magnetUri,
        description = null,
    )
}

/**
 * Reverse of [TopicDto.toTorrentItem]. Used when the
 * forward mapper recorded `metadata["rutracker.kind"] = "topic"` to mark
 * the row as a thin TopicDto rather than a full TorrentDto. TopicDto
 * carries no seed/leech/size/magnet/date fields.
 */
private fun TorrentItem.toTopicDto(): TopicDto {
    val item = this
    val categoryId = item.metadata["rutracker.categoryId"]
    val categoryName = item.metadata["rutracker.categoryName"] ?: item.category
    val category: CategoryDto? = if (categoryId != null || categoryName != null) {
        CategoryDto(id = categoryId, name = categoryName.orEmpty())
    } else {
        null
    }
    val authorId = item.metadata["rutracker.authorId"]
    val author: AuthorDto? = authorId?.let { AuthorDto(id = it, name = it) }
    return TopicDto(
        id = item.torrentId,
        title = item.title,
        author = author,
        category = category,
    )
}

/**
 * Discriminate between `TorrentDto` and `TopicDto` based on the
 * `rutracker.kind` metadata key set by the forward mappers
 * (CategoryPageMapper.toTorrentItemOrNull marks TopicDto rows with
 * `"rutracker.kind" = "topic"`).
 *
 * Note (clause D adaptation): the reverse mapper cannot fully discriminate
 * `CommentsPageDto` here because the forward path drops it on browse pages.
 * Per Section D's `CategoryPageMapper.toTorrentItemOrNull`, CommentsPageDto
 * inputs collapse to null and therefore never reach this reverse path.
 */
private fun TorrentItem.toForumTopicDto(): ForumTopicDto =
    when (metadata["rutracker.kind"]) {
        "topic" -> toTopicDto()
        else -> toTorrentDto()
    }

/**
 * Reverse of [CategoryDto.toForumCategory]. Restores `id = null` when the
 * forward mapper collapsed it to "" (Section D ForumDtoMapper documents
 * this empty-string-as-null contract).
 */
private fun ForumCategory.toCategoryDto(): CategoryDto {
    val mappedChildren = children.takeIf { it.isNotEmpty() }
        ?.map { it.toCategoryDto() }
    return CategoryDto(
        id = id.takeIf { it.isNotEmpty() },
        name = name,
        children = mappedChildren,
    )
}
