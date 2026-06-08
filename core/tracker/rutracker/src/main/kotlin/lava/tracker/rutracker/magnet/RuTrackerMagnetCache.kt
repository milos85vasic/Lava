package lava.tracker.rutracker.magnet

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, process-lifetime cache of magnet URIs keyed by RuTracker torrent id.
 *
 * RuTracker parses the magnet from the topic page and from search-result rows;
 * the production mappers ([lava.tracker.rutracker.mapper.TopicMapper] /
 * [lava.tracker.rutracker.mapper.SearchPageMapper]) populate
 * [lava.tracker.api.model.TorrentItem.magnetUri]. This cache lets the
 * SYNCHRONOUS, non-suspend
 * [lava.tracker.api.feature.DownloadableTracker.getMagnetLink] surface that
 * genuinely-parsed magnet once a topic/search fetch has populated it — without
 * an extra HTTP round-trip and without fabricating a value.
 *
 * §6.E Capability Honesty: [lava.tracker.rutracker.RuTrackerDescriptor] declares
 * `MAGNET_LINK`. Before this cache, the only reachable `getMagnetLink`
 * implementation ([lava.tracker.rutracker.domain.GetMagnetLinkUseCase]) returned
 * `null` unconditionally even though the magnet had been parsed milliseconds
 * earlier — the exact bluff RuTor closed with `RuTorMagnetCache` (audit:
 * docs/qa/magnet-label-honesty-audit-2026-06-08.md W4a). This cache is the path
 * that makes `getMagnetLink` resolve to the real magnet. When an id has never
 * been surfaced, [get] returns null — an honest absence, exactly the
 * `DownloadableTracker` contract's "not synchronously available without an HTTP
 * fetch" case.
 *
 * Thread-safe: backed by a [ConcurrentHashMap]; the search and topic features
 * (potentially different coroutines) write while download reads. Singleton-scoped
 * so the topic/search features and the download feature share one instance per
 * process (the clone path in [lava.tracker.rutracker.RuTrackerSubgraphBuilder]
 * passes an explicit shared instance).
 */
@Singleton
class RuTrackerMagnetCache @Inject constructor() {

    private val byTorrentId = ConcurrentHashMap<String, String>()

    /** Records a genuinely-parsed magnet for [torrentId]; blank inputs are ignored. */
    fun put(torrentId: String, magnet: String?) {
        if (torrentId.isBlank() || magnet.isNullOrBlank()) return
        byTorrentId[torrentId] = magnet
    }

    /** Returns the cached magnet for [torrentId], or null if none was surfaced yet. */
    fun get(torrentId: String): String? = byTorrentId[torrentId]
}
