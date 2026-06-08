package lava.tracker.nnmclub.http

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, process-lifetime cache of magnet URIs keyed by NNM-Club topic id.
 *
 * NNM-Club embeds the magnet directly in the topic page (`viewtopic.php?t=<id>`)
 * and in search-result rows. Both are parsed by the production parsers into
 * `magnetUri`. This cache lets the SYNCHRONOUS, non-suspend
 * [lava.tracker.api.feature.DownloadableTracker.getMagnetLink] surface that
 * genuinely-parsed magnet once a topic/search fetch has populated it — without
 * an extra HTTP round-trip and without fabricating a value.
 *
 * §6.E Capability Honesty: the descriptor declares `MAGNET_LINK`; this cache is
 * the path that makes `getMagnetLink` resolve to a real magnet instead of an
 * unconditional `null`. When an id has never been surfaced, `get` returns null
 * — an honest absence, exactly the `DownloadableTracker` contract's "not
 * synchronously available without an HTTP fetch" case.
 *
 * Thread-safe: backed by a [ConcurrentHashMap]; the search and topic features
 * (potentially different coroutines) write while download reads.
 */
@Singleton
class NnmclubMagnetCache @Inject constructor() {

    private val byTopicId = ConcurrentHashMap<String, String>()

    /** Records a genuinely-parsed magnet for [topicId]; blank inputs are ignored. */
    fun put(topicId: String, magnet: String?) {
        if (topicId.isBlank() || magnet.isNullOrBlank()) return
        byTopicId[topicId] = magnet
    }

    /** Returns the cached magnet for [topicId], or null if none was surfaced yet. */
    fun get(topicId: String): String? = byTopicId[topicId]
}
