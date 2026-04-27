package lava.data.converters

import lava.database.entity.SearchHistoryEntity
import lava.database.entity.SuggestEntity
import lava.models.Page
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Search
import lava.models.search.Sort
import lava.models.topic.Torrent
import lava.network.dto.search.SearchPageDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.TorrentDto

internal fun SearchPageDto.toSearchPage(): Page<Torrent> {
    return Page(
        page = page,
        pages = pages,
        items = torrents.map(TorrentDto::toTorrent),
    )
}

internal fun Period.toDto(): SearchPeriodDto = when (this) {
    Period.ALL_TIME -> SearchPeriodDto.AllTime
    Period.TODAY -> SearchPeriodDto.Today
    Period.LAST_THREE_DAYS -> SearchPeriodDto.LastThreeDays
    Period.LAST_WEEK -> SearchPeriodDto.LastWeek
    Period.LAST_TWO_WEEKS -> SearchPeriodDto.LastTwoWeeks
    Period.LAST_MONTH -> SearchPeriodDto.LastMonth
}

internal fun Sort.toDto(): SearchSortTypeDto = when (this) {
    Sort.DATE -> SearchSortTypeDto.Date
    Sort.TITLE -> SearchSortTypeDto.Title
    Sort.DOWNLOADED -> SearchSortTypeDto.Downloaded
    Sort.SEEDS -> SearchSortTypeDto.Seeds
    Sort.LEECHES -> SearchSortTypeDto.Leeches
    Sort.SIZE -> SearchSortTypeDto.Size
}

internal fun Order.toDto(): SearchSortOrderDto = when (this) {
    Order.ASCENDING -> SearchSortOrderDto.Ascending
    Order.DESCENDING -> SearchSortOrderDto.Descending
}

internal fun SearchHistoryEntity.toSearch(): Search {
    return Search(
        id = id,
        filter = Filter(
            query = query,
            sort = sort,
            order = order,
            period = period,
            author = author,
            categories = categories,
        ),
    )
}

internal fun Filter.toEntity(): SearchHistoryEntity {
    return SearchHistoryEntity(
        id = id(),
        timestamp = System.currentTimeMillis(),
        query = query,
        sort = sort,
        order = order,
        period = period,
        author = author,
        categories = categories,
    )
}

private fun Filter.id(): Int {
    var id = query?.hashCode() ?: 0
    id = 31 * id + period.ordinal
    id = 31 * id + (author?.id?.hashCode() ?: 0)
    id = 31 * id + (categories?.sumOf(Category::hashCode) ?: 0)
    return id
}

internal fun String.toEntity(): SuggestEntity {
    return SuggestEntity(
        id = lowercase().hashCode(),
        timestamp = System.currentTimeMillis(),
        suggest = this,
    )
}
