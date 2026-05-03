package lava.tracker.archiveorg.feature

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem

@Serializable
internal data class SearchResponseDto(
    val response: ResponseDto,
) {
    fun toDomain(page: Int): SearchResult {
        val items = response.docs.map { it.toDomain() }
        val totalPages = (response.numFound / 50).let {
            if (response.numFound % 50 != 0) it + 1 else it
        }.coerceAtLeast(1)
        return SearchResult(
            items = items,
            totalPages = totalPages,
            currentPage = page,
        )
    }

    fun toBrowseResult(page: Int): BrowseResult {
        val items = response.docs.map { it.toDomain() }
        val totalPages = (response.numFound / 50).let {
            if (response.numFound % 50 != 0) it + 1 else it
        }.coerceAtLeast(1)
        return BrowseResult(
            items = items,
            totalPages = totalPages,
            currentPage = page,
        )
    }
}

@Serializable
internal data class ResponseDto(
    @SerialName("numFound") val numFound: Int,
    val start: Int,
    val docs: List<SearchDocDto>,
)

@Serializable
internal data class SearchDocDto(
    val identifier: String,
    val title: String,
    val creator: String? = null,
    val downloads: Int? = null,
    @SerialName("item_size") val itemSize: Long? = null,
    val mediatype: String? = null,
    val year: String? = null,
) {
    fun toDomain(): TorrentItem = TorrentItem(
        trackerId = "archiveorg",
        torrentId = identifier,
        title = title,
        sizeBytes = itemSize,
        category = mediatype,
        metadata = buildMap {
            creator?.let { put("creator", it) }
            downloads?.let { put("downloads", it.toString()) }
            year?.let { put("year", it) }
        },
    )
}
