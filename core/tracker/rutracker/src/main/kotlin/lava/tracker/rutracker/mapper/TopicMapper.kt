package lava.tracker.rutracker.mapper

import kotlinx.datetime.Instant
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDataDto
import lava.network.dto.topic.TorrentDto
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Maps the legacy [ForumTopicDto] / [TopicPageDto] (rutracker topic + page
 * scrapes) to the new tracker-api [TopicDetail] / [TopicPage].
 *
 * The DTO returned by GetTopicUseCase is a sealed [ForumTopicDto] (Topic /
 * Torrent / CommentsPage); the mapper handles all branches:
 *  - [TorrentDto] → TopicDetail with magnet, seeds/leeches, description
 *    flattened from rich post elements to plain text.
 *  - [TopicDto]   → TopicDetail wrapping a "thin" TorrentItem.
 *  - [CommentsPageDto] → TopicDetail wrapping a "thin" TorrentItem with
 *    metadata["rutracker.kind"] = "comments" so callers can detect that
 *    they should use CommentsTracker for the actual content. (This branch
 *    is unusual but the legacy parser does emit it for topics that have
 *    no torrent attachment; callers should usually invoke getTopicPage
 *    instead, but the contract requires us to map every sealed branch.)
 *
 * Information-loss notes (relevant to Section E):
 *  - The legacy TopicPageDto carries TorrentDataDto.date as a String (the
 *    rutracker scraper's pre-formatted display date). We attempt
 *    Instant.parse first (works if the scraper emits ISO-8601), and fall
 *    back to null on parse failure. The original string survives in
 *    metadata["rutracker.date_text"] so the reverse mapper can preserve
 *    user-visible formatting.
 *  - TorrentDataDto.size is also pre-formatted; preserved in
 *    metadata["rutracker.size_text"], TorrentItem.sizeBytes stays null.
 *  - File listings are not surfaced on rutracker's topic-page scrape
 *    today; TopicDetail.files is always emptyList().
 */
class TopicMapper @Inject constructor() {
    fun toTopicDetail(dto: ForumTopicDto): TopicDetail = when (dto) {
        is TorrentDto -> TopicDetail(
            torrent = dto.toTorrentItem(),
            description = dto.description?.children?.flattenToText().orEmpty()
                .takeIf { it.isNotBlank() },
            files = emptyList(),
        )
        is TopicDto -> TopicDetail(torrent = dto.toTorrentItem())
        is CommentsPageDto -> TopicDetail(torrent = dto.toThinTorrentItem())
    }

    fun toTopicPage(dto: TopicPageDto, currentPage: Int): TopicPage {
        val item = dto.toTorrentItem()
        val description: String? = null
        return TopicPage(
            topic = TopicDetail(
                torrent = item,
                description = description,
                files = emptyList(),
            ),
            totalPages = dto.commentsPage.pages,
            currentPage = currentPage,
        )
    }
}

private fun CommentsPageDto.toThinTorrentItem(): TorrentItem {
    val dto = this
    val metadata = buildMap<String, String> {
        dto.category?.id?.let { put("rutracker.categoryId", it) }
        dto.category?.name?.let { put("rutracker.categoryName", it) }
        dto.author?.id?.let { put("rutracker.authorId", it) }
        put("rutracker.kind", "comments")
    }
    return TorrentItem(
        trackerId = "rutracker",
        torrentId = id,
        title = title,
        category = category?.name,
        metadata = metadata,
    )
}

private fun TopicPageDto.toTorrentItem(): TorrentItem {
    val dto = this
    val data: TorrentDataDto? = dto.torrentData
    val metadata = buildMap<String, String> {
        dto.category?.id?.let { put("rutracker.categoryId", it) }
        dto.category?.name?.let { put("rutracker.categoryName", it) }
        dto.author?.id?.let { put("rutracker.authorId", it) }
        data?.tags?.takeIf { it.isNotBlank() }?.let { put("rutracker.tags", it) }
        data?.status?.let { put("rutracker.status", it.name) }
        data?.size?.let { put("rutracker.size_text", it) }
        data?.date?.let { put("rutracker.date_text", it) }
        data?.posterUrl?.let { put("rutracker.posterUrl", it) }
    }
    val publish = data?.date?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }
    return TorrentItem(
        trackerId = "rutracker",
        torrentId = id,
        title = title,
        sizeBytes = null,
        seeders = data?.seeds,
        leechers = data?.leeches,
        infoHash = null,
        magnetUri = data?.magnetLink,
        downloadUrl = null,
        detailUrl = null,
        category = category?.name,
        publishDate = publish,
        metadata = metadata,
    )
}
