package lava.tracker.rutor.magnet

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, process-lifetime cache of magnet URIs keyed by RuTor torrent id.
 *
 * RuTor embeds the magnet directly in the topic page (`/torrent/<id>`) and in
 * search-result rows (`/search/...`). Both are parsed by the production parsers
 * ([lava.tracker.rutor.parser.RuTorTopicParser] /
 * [lava.tracker.rutor.parser.RuTorSearchParser]) into `magnetUri`. This cache
 * lets the SYNCHRONOUS, non-suspend
 * [lava.tracker.api.feature.DownloadableTracker.getMagnetLink] surface that
 * genuinely-parsed magnet once a topic/search fetch has populated it — without
 * an extra HTTP round-trip and without fabricating a value.
 *
 * §6.E Capability Honesty: [lava.tracker.rutor.RuTorDescriptor] declares
 * `MAGNET_LINK`; this cache is the path that makes `getMagnetLink` resolve to a
 * real magnet instead of an unconditional `null`. When an id has never been
 * surfaced, [get] returns null — an honest absence, exactly the
 * `DownloadableTracker` contract's "not synchronously available without an HTTP
 * fetch" case.
 *
 * Thread-safe: backed by a [ConcurrentHashMap]; the search and topic features
 * (potentially different coroutines) write while download reads. Singleton-scoped
 * so the topic/search features and the download feature share one instance per
 * process (the clone path passes an explicit shared instance).
 */
@Singleton
class RuTorMagnetCache @Inject constructor() {

    private val byTorrentId = ConcurrentHashMap<String, String>()

    /** Records a genuinely-parsed magnet for [torrentId]; blank inputs are ignored. */
    fun put(torrentId: String, magnet: String?) {
        if (torrentId.isBlank() || magnet.isNullOrBlank()) return
        byTorrentId[torrentId] = magnet
    }

    /** Returns the cached magnet for [torrentId], or null if none was surfaced yet. */
    fun get(torrentId: String): String? = byTorrentId[torrentId]
}
