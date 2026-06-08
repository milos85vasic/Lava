package lava.tracker.iptorrents.model

import kotlinx.serialization.json.Json
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Maps the lava-api-go `/jackett/search` JSON wire format
 * ([JackettSearchResponseDto]) into the SDK domain [SearchResult] / [TorrentItem]
 * the rest of Lava consumes.
 *
 * Field correspondence (route JSON → TorrentItem), all derived from
 * `lava-api-go/internal/handlers/v1/jackett.go` `mapJackettResults`:
 *   - id          → torrentId   (the Torznab GUID; the stable item identifier)
 *   - title       → title
 *   - sizeBytes   → sizeBytes
 *   - seeders     → seeders     (route omits the field for unknown counts)
 *   - infoHash    → infoHash
 *   - magnetLink  → magnetUri   (magnet enclosure OR magneturl attr)
 *   - downloadUrl → downloadUrl (.torrent Jackett /dl/ proxy link)
 *   - category    → category    (the route stamps the indexer id here)
 */
class JackettResultMapper @Inject constructor() {

    private val json: Json = DEFAULT_JSON

    /** Parses the raw route JSON body and maps it to a domain [SearchResult]. */
    fun map(trackerId: String, body: String): SearchResult {
        val dto = json.decodeFromString(JackettSearchResponseDto.serializer(), body)
        val items = dto.results.map { it.toTorrentItem(trackerId) }
        return SearchResult(
            items = items,
            // The route is a flat (non-paginated) Torznab query — page/total are 1.
            totalPages = dto.totalPages,
            currentPage = dto.page - 1, // route is 1-based; SDK is 0-based.
        )
    }

    private fun JackettSearchItemDto.toTorrentItem(trackerId: String): TorrentItem = TorrentItem(
        trackerId = trackerId,
        torrentId = id,
        title = title,
        sizeBytes = sizeBytes,
        seeders = seeders,
        infoHash = infoHash?.takeIf { it.isNotBlank() },
        magnetUri = magnetLink?.takeIf { it.isNotBlank() },
        downloadUrl = downloadUrl?.takeIf { it.isNotBlank() },
        category = category?.takeIf { it.isNotBlank() },
    )

    companion object {
        val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true }
    }
}
