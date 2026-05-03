package lava.tracker.client

import lava.tracker.api.model.TorrentItem

/**
 * Result of a multi-provider unified search.
 *
 * Each [TorrentItem] is wrapped with metadata about which providers returned it,
 * so the UI can render multi-provider badges and let the user pick their
 * preferred source for download.
 *
 * Added in Multi-Provider Extension (Task 7.3).
 */
data class UnifiedSearchResult(
    val query: String,
    val page: Int,
    val items: List<UnifiedTorrentItem>,
    val totalPages: Int,
    val providerStatuses: List<ProviderSearchStatus>,
)

/**
 * A single torrent item with provider-occurrence metadata.
 */
data class UnifiedTorrentItem(
    val torrent: TorrentItem,
    val occurrences: List<ProviderOccurrence>,
    val primaryProvider: String,
)

/**
 * Metadata about one provider's copy of a torrent.
 */
data class ProviderOccurrence(
    val providerId: String,
    val providerDisplayName: String,
    val torrentId: String,
    val seeders: Int?,
    val leechers: Int?,
    val sizeBytes: Long?,
    val magnetUri: String?,
    val downloadUrl: String?,
)

/**
 * Status of a single provider's participation in a unified search.
 */
data class ProviderSearchStatus(
    val providerId: String,
    val displayName: String,
    val state: ProviderSearchState,
    val resultCount: Int = 0,
    val errorMessage: String? = null,
)

enum class ProviderSearchState {
    PENDING,
    LOADING,
    SUCCESS,
    FAILURE,
    UNSUPPORTED,
}
