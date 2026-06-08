package lava.tracker.iptorrents.model

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-torrent-id cache of the download surfaces a search row carried, so the
 * synchronous [lava.tracker.iptorrents.feature.IPTorrentsDownload.getMagnetLink]
 * and the id-keyed [IPTorrentsDownload.downloadTorrentFile] can resolve a
 * magnet / .torrent URL WITHOUT a second round-trip — the `/jackett/search`
 * route already handed both back on the search response.
 *
 * Mirrors the role of rutor's `RuTorMagnetCache`. Thread-safe; survives for the
 * process lifetime (singleton).
 */
@Singleton
class IPTorrentsResultCache @Inject constructor() {

    private val magnets = ConcurrentHashMap<String, String>()
    private val downloadUrls = ConcurrentHashMap<String, String>()

    /** Records the magnet + .torrent download URL a search row carried for [torrentId]. */
    fun put(torrentId: String, magnetUri: String?, downloadUrl: String?) {
        if (torrentId.isBlank()) return
        magnetUri?.takeIf { it.isNotBlank() }?.let { magnets[torrentId] = it }
        downloadUrl?.takeIf { it.isNotBlank() }?.let { downloadUrls[torrentId] = it }
    }

    /** Magnet URI previously seen for [torrentId], or null. */
    fun magnetFor(torrentId: String): String? = magnets[torrentId]

    /** .torrent download URL previously seen for [torrentId], or null. */
    fun downloadUrlFor(torrentId: String): String? = downloadUrls[torrentId]
}
