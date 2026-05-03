package lava.tracker.client.dedup

import lava.tracker.api.model.TorrentItem
import lava.tracker.client.ProviderOccurrence
import lava.tracker.client.UnifiedTorrentItem

/**
 * Deduplicates torrent items across multiple provider results.
 *
 * Matching strategy (in priority order):
 *  1. **Info-hash exact match** — if two items have the same non-null,
 *     non-empty infoHash, they are the same torrent.
 *  2. **Title + size fuzzy match** — normalized title (lowercased, whitespace
 *     collapsed) and size within 1% tolerance. Used when info-hash is absent
 *     (common for HTTP-library providers).
 *  3. **Title exact match** — fallback when size is unavailable.
 *
 * Constitutional alignment:
 *   - 6.E Capability Honesty: the engine NEVER invents an info-hash match
 *     when none exists. A missing infoHash is treated as "cannot verify
 *     identity via hash", falling back to the weaker title+size heuristic.
 *   - 6.D Behavioral Coverage: every matching branch has a dedicated unit test
 *     with a falsifiability rehearsal (deliberately break the heuristic and
 *     confirm the test fails).
 *
 * Added in Multi-Provider Extension (Task 7.4).
 */
object DeduplicationEngine {

    /**
     * Merges provider-specific result lists into a deduplicated list of
     * [UnifiedTorrentItem]s, preserving the order of first occurrence.
     */
    fun deduplicate(
        providerResults: Map<String, List<TorrentItem>>,
        providerDisplayNames: Map<String, String>,
    ): List<UnifiedTorrentItem> {
        val groups = mutableListOf<MutableList<Occurrence>>()

        for ((providerId, items) in providerResults) {
            for (item in items) {
                val occurrence = item.toOccurrence(providerId, providerDisplayNames[providerId] ?: providerId)
                val matchedGroup = groups.firstOrNull { group ->
                    group.any { existing -> matches(existing.item, item) }
                }
                if (matchedGroup != null) {
                    matchedGroup.add(occurrence)
                } else {
                    groups.add(mutableListOf(occurrence))
                }
            }
        }

        return groups.map { group ->
            val primary = group.first()
            UnifiedTorrentItem(
                torrent = primary.item,
                occurrences = group.map { it.toProviderOccurrence() },
                primaryProvider = primary.providerId,
            )
        }
    }

    /**
     * Returns true when [a] and [b] refer to the same content.
     */
    internal fun matches(a: TorrentItem, b: TorrentItem): Boolean {
        // Strategy 1: info-hash exact match.
        if (!a.infoHash.isNullOrBlank() && !b.infoHash.isNullOrBlank()) {
            return a.infoHash.equals(b.infoHash, ignoreCase = true)
        }

        // Strategy 2: normalized title + size tolerance.
        val titleMatch = a.title.normalize() == b.title.normalize()
        if (titleMatch) {
            val sizeA = a.sizeBytes
            val sizeB = b.sizeBytes
            if (sizeA != null && sizeB != null) {
                return sizeWithinTolerance(sizeA, sizeB)
            }
            // Strategy 3: title exact match when size unavailable.
            if (sizeA == null && sizeB == null) {
                return true
            }
        }
        return false
    }

    private fun sizeWithinTolerance(a: Long, b: Long, tolerancePercent: Double = 1.0): Boolean {
        if (a == b) return true
        val diff = kotlin.math.abs(a - b)
        val avg = (a + b) / 2.0
        return (diff / avg) * 100.0 <= tolerancePercent
    }

    private fun String.normalize(): String =
        lowercase()
            .replace(Regex("""[\s\-_]+"""), " ")
            .trim()

    private data class Occurrence(
        val item: TorrentItem,
        val providerId: String,
        val providerDisplayName: String,
        val torrentId: String,
        val seeders: Int?,
        val leechers: Int?,
        val sizeBytes: Long?,
        val magnetUri: String?,
        val downloadUrl: String?,
    )

    private fun TorrentItem.toOccurrence(providerId: String, displayName: String): Occurrence =
        Occurrence(
            item = this,
            providerId = providerId,
            providerDisplayName = displayName,
            torrentId = this.torrentId,
            seeders = this.seeders,
            leechers = this.leechers,
            sizeBytes = this.sizeBytes,
            magnetUri = this.magnetUri,
            downloadUrl = this.downloadUrl,
        )

    private fun Occurrence.toProviderOccurrence(): ProviderOccurrence =
        ProviderOccurrence(
            providerId = providerId,
            providerDisplayName = providerDisplayName,
            torrentId = torrentId,
            seeders = seeders,
            leechers = leechers,
            sizeBytes = sizeBytes,
            magnetUri = magnetUri,
            downloadUrl = downloadUrl,
        )
}
