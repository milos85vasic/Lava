package lava.tracker.iptorrents.model

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the lava-api-go `GET /jackett/search` response.
 *
 * These mirror — field-for-field — the JSON the route ACTUALLY emits, which is
 * a `provider.SearchResult` re-serialized from the parsed Torznab feed (NOT raw
 * Torznab XML). Source of truth:
 *   - `lava-api-go/internal/handlers/v1/jackett.go` `mapJackettResults`
 *   - `lava-api-go/internal/provider/provider.go` `SearchResult` / `SearchItem`
 *
 * Honesty note (§6.J): these field names are the format lava-api-go genuinely
 * produces, so a fixture built from them tests the REAL contract — not invented
 * site HTML (which is impossible to fetch behind Cloudflare anyway).
 *
 * Only the subset the IPTorrents provider consumes is declared; unknown keys are
 * ignored by the parser (`ignoreUnknownKeys = true`) so additional SearchItem
 * fields (leechers, date, thumbnailUrl, …) that the route may emit do not break
 * decoding.
 */
@Serializable
data class JackettSearchResponseDto(
    val provider: String = "",
    val page: Int = 1,
    val totalPages: Int = 1,
    val results: List<JackettSearchItemDto> = emptyList(),
)

@Serializable
data class JackettSearchItemDto(
    val id: String = "",
    val title: String = "",
    val size: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val date: String? = null,
    val category: String? = null,
    val downloadUrl: String? = null,
    val magnetLink: String? = null,
    val infoHash: String? = null,
)
