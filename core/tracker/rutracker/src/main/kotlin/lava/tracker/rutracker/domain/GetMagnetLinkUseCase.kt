package lava.tracker.rutracker.domain

/**
 * Minimal stub for the rutracker magnet-link lookup. Magnet URIs require
 * fetching the topic page (lazily, via [GetTopicUseCase]); they are NOT
 * synchronously available without an HTTP fetch.
 *
 * The synchronous variant exposed by [DownloadableTracker.getMagnetLink]
 * is therefore null here. Section F may upgrade this to consult an
 * in-memory cache populated by previous topic fetches.
 */
class GetMagnetLinkUseCase {
    operator fun invoke(id: String): String? = null
}
