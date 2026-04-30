package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature

interface DownloadableTracker : TrackerFeature {
    suspend fun downloadTorrentFile(id: String): ByteArray

    /** Returns null if the magnet URI is not synchronously available without an HTTP fetch. */
    fun getMagnetLink(id: String): String?
}
