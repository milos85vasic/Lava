package lava.tracker.rutracker.mapper

import kotlinx.datetime.Instant
import lava.network.dto.search.SearchPageDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.TorrentDto
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.SortField
import lava.tracker.api.model.SortOrder
import lava.tracker.api.model.TimePeriod
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Maps the legacy [SearchPageDto] (rutracker scraper output) to the new
 * tracker-api [SearchResult].
 *
 * Information-loss notes (relevant to Section E reverse mapping):
 *  - The legacy [TorrentDto.size] is already formatted (e.g. "1.2 GB"); the
 *    raw byte count is dropped by the scraper before this mapper sees it.
 *    We preserve the formatted string in metadata under "rutracker.size_text"
 *    AND, since LF-6 was resolved on 2026-04-30, parse it back into a
 *    [TorrentItem.sizeBytes] via [RuTrackerSizeParser]. The parser tolerates
 *    null/blank input and unparseable strings by returning null — i.e. the
 *    field can still be null when the upstream DTO had no size at all, but
 *    is never null when the DTO carried a parseable formatted string.
 *  - The legacy [TorrentDto.date] is an epoch-seconds Long when present.
 *    Malformed values are tolerated by yielding null.
 */
class SearchPageMapper @Inject constructor() {
    fun toSearchResult(dto: SearchPageDto, currentPage: Int): SearchResult {
        val items = dto.torrents.map { it.toTorrentItem() }
        return SearchResult(
            items = items,
            totalPages = dto.pages,
            currentPage = currentPage,
        )
    }
}

internal fun TorrentDto.toTorrentItem(): TorrentItem {
    val torrent = this
    val metadata = buildMap<String, String> {
        torrent.category?.id?.let { put("rutracker.categoryId", it) }
        torrent.category?.name?.let { put("rutracker.categoryName", it) }
        torrent.author?.id?.let { put("rutracker.authorId", it) }
        torrent.tags?.takeIf { it.isNotBlank() }?.let { put("rutracker.tags", it) }
        torrent.status?.let { put("rutracker.status", it.name) }
        torrent.size?.let { put("rutracker.size_text", it) }
    }
    val publish = date?.let {
        runCatching { Instant.fromEpochSeconds(it) }.getOrNull()
    }
    return TorrentItem(
        trackerId = "rutracker",
        torrentId = id,
        title = title,
        // LF-6 RESOLVED 2026-04-30: parse the formatted display string
        // ("4.7 GB") back into a binary byte count. Forward-leaning
        // consumers (cross-tracker fallback ranking, size-based filters)
        // can now rely on a non-null value when the upstream DTO carries
        // a parseable size.
        sizeBytes = RuTrackerSizeParser.parse(torrent.size),
        seeders = seeds,
        leechers = leeches,
        infoHash = null,
        magnetUri = magnetLink,
        downloadUrl = null,
        detailUrl = null,
        category = category?.name,
        publishDate = publish,
        metadata = metadata,
    )
}

/**
 * Carrier for the legacy positional parameters expected by
 * [lava.tracker.rutracker.domain.GetSearchPageUseCase.invoke]. Lets the new
 * [SearchRequest] (which collapses sort/order/period into typed enums) be
 * unpacked back into the strings the inner API expects.
 */
data class LegacySearchParams(
    val sortType: SearchSortTypeDto?,
    val sortOrder: SearchSortOrderDto?,
    val period: SearchPeriodDto?,
    val categories: String?,
)

/**
 * Reverse mapping (model → legacy DTO enums).
 *
 * - `SortField.RELEVANCE` has no rutracker equivalent (the site sorts by
 *   `Date` / `Title` / `Downloaded` / `Seeds` / `Leeches` / `Size`); per
 *   the plan we collapse RELEVANCE to `Date` to preserve a usable default.
 * - `TimePeriod.LAST_YEAR` has no rutracker equivalent (the site offers
 *   `Today` / `LastThreeDays` / `LastWeek` / `LastTwoWeeks` / `LastMonth`
 *   / `AllTime`); we collapse LAST_YEAR to `AllTime` so the search still
 *   returns broadly-relevant results rather than failing. Callers that
 *   want exact "last year" filtering must downgrade to `LAST_MONTH` or
 *   accept the wider `AllTime` window.
 * - `categories` becomes a comma-joined string, the form rutracker's `f=`
 *   query parameter expects. An empty list collapses to `null` (no filter).
 */
fun SearchRequest.toLegacySearchParams(): LegacySearchParams {
    val sortType = when (sort) {
        SortField.DATE -> SearchSortTypeDto.Date
        SortField.SEEDERS -> SearchSortTypeDto.Seeds
        SortField.LEECHERS -> SearchSortTypeDto.Leeches
        SortField.SIZE -> SearchSortTypeDto.Size
        SortField.TITLE -> SearchSortTypeDto.Title
        SortField.RELEVANCE -> SearchSortTypeDto.Date
    }
    val orderDto = when (sortOrder) {
        SortOrder.ASCENDING -> SearchSortOrderDto.Ascending
        SortOrder.DESCENDING -> SearchSortOrderDto.Descending
    }
    val periodDto = period?.let {
        when (it) {
            TimePeriod.LAST_DAY -> SearchPeriodDto.Today
            TimePeriod.LAST_WEEK -> SearchPeriodDto.LastWeek
            TimePeriod.LAST_MONTH -> SearchPeriodDto.LastMonth
            TimePeriod.LAST_YEAR -> SearchPeriodDto.AllTime
            TimePeriod.ALL_TIME -> SearchPeriodDto.AllTime
        }
    }
    return LegacySearchParams(
        sortType = sortType,
        sortOrder = orderDto,
        period = periodDto,
        categories = categories.takeIf { it.isNotEmpty() }?.joinToString(","),
    )
}
