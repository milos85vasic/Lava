package lava.downloads.impl

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe id → file-URI cache for completed torrent downloads.
 *
 * Extracted from [DownloadServiceImpl] (completeness Phase 3, leak/race
 * sweep) because the map is read on the caller's coroutine dispatcher
 * (`downloadTorrentFile`) and written from the `DownloadManager`
 * `BroadcastReceiver.onReceive` callback, which Android delivers on the
 * MAIN thread. Concurrent downloads therefore touch the same map from two
 * different threads. The previous `mutableMapOf()` (a `HashMap`) is not
 * thread-safe: concurrent structural modification can lose writes, throw
 * `ConcurrentModificationException`, or (historically) spin in an infinite
 * resize loop. Backing it with [ConcurrentHashMap] removes the race while
 * keeping the same get/put semantics.
 *
 * Kept as a plain JVM class (no Android dependency) so the concurrency
 * guarantee is unit-testable without an emulator (Fifth Law: refactor for
 * testability).
 */
internal class DownloadUriCache {
    private val entries = ConcurrentHashMap<String, String>()

    /** Returns the cached file URI for [id], or null if none is cached. */
    fun get(id: String): String? = entries[id]

    /** Caches [fileUri] under [id]. Safe to call from any thread. */
    fun put(id: String, fileUri: String) {
        entries[id] = fileUri
    }

    /** Removes any cached URI for [id] (e.g. a cached-but-corrupt file). */
    fun remove(id: String) {
        entries.remove(id)
    }
}
