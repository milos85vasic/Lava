package lava.tracker.rutracker.mapper

import lava.network.dto.user.FavoritesDto
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Maps the legacy [FavoritesDto] (rutracker bookmarks scrape) to the new
 * tracker-api list of [TorrentItem].
 *
 * FavoritesDto.topics is a sealed `List<ForumTopicDto>` (same as
 * CategoryPageDto's topics list); we reuse the shared
 * [ForumTopicDto.toTorrentItemOrNull] extension defined alongside
 * CategoryPageMapper. CommentsPageDto entries (which can technically appear
 * if the user bookmarks a comment-only thread) are silently skipped via
 * mapNotNull rather than throwing.
 */
class FavoritesMapper @Inject constructor() {
    fun toTorrentItems(dto: FavoritesDto): List<TorrentItem> =
        dto.topics.mapNotNull { it.toTorrentItemOrNull() }
}
