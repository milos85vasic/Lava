package lava.tracker.kinozal.magnet

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache mapping a Kinozal topic id to the magnet URI parsed from
 * that topic's page.
 *
 * Why this exists (§6.E Capability Honesty): the Kinozal descriptor declares
 * [lava.tracker.api.TrackerCapability.MAGNET_LINK], and the topic page DOES
 * carry a real magnet (extracted by `KinozalTopicParser`). But the magnet is
 * NOT synchronously resolvable from a bare topic id — it requires an HTTP
 * fetch of the topic page. [lava.tracker.api.feature.DownloadableTracker.getMagnetLink]
 * is a synchronous (non-suspend) method whose contract is to return null when
 * the magnet "is not synchronously available without an HTTP fetch".
 *
 * This cache is the documented "in-memory cache populated by previous topic
 * fetches" pattern (the same upgrade path noted for RuTracker's
 * `GetMagnetLinkUseCase`). `KinozalTopic.getTopic` writes the parsed magnet
 * here; `KinozalDownload.getMagnetLink` reads it. The result: once the user
 * has opened a topic (the production path that already fetches the page), the
 * synchronous magnet lookup returns the REAL magnet — no fabrication, no
 * hidden HTTP fetch inside the synchronous method, no bluff.
 *
 * Singleton-scoped so the topic feature and the download feature share one
 * instance per process (the clone path passes an explicit shared instance).
 */
@Singleton
class KinozalMagnetCache @Inject constructor() {

    private val byTopicId = ConcurrentHashMap<String, String>()

    /** Records the magnet for [topicId]. Ignores blank ids and magnets. */
    fun put(topicId: String, magnetUri: String) {
        if (topicId.isBlank() || magnetUri.isBlank()) return
        byTopicId[topicId] = magnetUri
    }

    /** Returns the cached magnet for [topicId], or null if no topic view has populated it yet. */
    fun get(topicId: String): String? = byTopicId[topicId]
}
