package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TorrentDto
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Maps the legacy [CategoryPageDto] (rutracker category browse scrape) to
 * the new tracker-api [BrowseResult].
 *
 * The legacy DTO carries a sealed `topics: List<ForumTopicDto>?` whose
 * subtypes are [TorrentDto], [TopicDto], and [CommentsPageDto]. Browse
 * pages only ever surface the first two; CommentsPageDto would never
 * appear here in practice but we tolerate it (skip) rather than throw.
 *
 * Information-loss notes:
 *  - TopicDto carries no seed/leech/size; the resulting TorrentItem has
 *    null seeders/leechers/sizeBytes for those rows. metadata still
 *    captures category/author/tags so the reverse mapping (Section E)
 *    can decide whether to project as TopicDto vs TorrentDto.
 *  - The new ForumCategory.id is non-null. The legacy CategoryDto.id is
 *    nullable; we collapse null → "" to match ForumDtoMapper.
 */
class CategoryPageMapper @Inject constructor() {
    fun toBrowseResult(dto: CategoryPageDto, currentPage: Int): BrowseResult {
        val items = (dto.topics ?: emptyList()).mapNotNull { it.toTorrentItemOrNull() }
        // Use the shared CategoryDto.toForumCategory(parentId) extension defined
        // in ForumDtoMapper.kt — browse pages don't carry the full child tree on
        // the category field itself, but the recursive helper handles `children
        // = null` (collapses to emptyList) so it's safe to reuse without changing
        // semantics.
        val category = dto.category.toForumCategory(parentId = null)
        return BrowseResult(
            items = items,
            totalPages = dto.pages,
            currentPage = currentPage,
            category = category,
        )
    }
}

internal fun ForumTopicDto.toTorrentItemOrNull(): TorrentItem? = when (val dto = this) {
    is TorrentDto -> dto.toTorrentItem()
    is TopicDto -> dto.toTorrentItem()
    is CommentsPageDto -> null
}

internal fun TopicDto.toTorrentItem(): TorrentItem {
    val topic = this
    val metadata = buildMap<String, String> {
        topic.category?.id?.let { put("rutracker.categoryId", it) }
        topic.category?.name?.let { put("rutracker.categoryName", it) }
        topic.author?.id?.let { put("rutracker.authorId", it) }
        put("rutracker.kind", "topic")
    }
    return TorrentItem(
        trackerId = "rutracker",
        torrentId = id,
        title = title,
        sizeBytes = null,
        seeders = null,
        leechers = null,
        infoHash = null,
        magnetUri = null,
        downloadUrl = null,
        detailUrl = null,
        category = category?.name,
        publishDate = null,
        metadata = metadata,
    )
}
