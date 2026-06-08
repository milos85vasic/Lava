package lava.tracker.iptorrents.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.iptorrents.http.IPTorrentsJackettApi
import lava.tracker.iptorrents.model.IPTorrentsResultCache
import javax.inject.Inject

/**
 * IPTorrents [DownloadableTracker] — backs both TORRENT_DOWNLOAD and MAGNET_LINK.
 *
 * Both surfaces resolve from [IPTorrentsResultCache], populated by
 * [IPTorrentsSearch] from the `/jackett/search` route response (which carries
 * `downloadUrl` = a Jackett /dl/ .torrent proxy link, and `magnetLink` = the
 * magnet enclosure / magneturl attr). No second search round-trip is needed.
 *
 * §6.E Capability Honesty: these are NON-empty implementations of the exact two
 * download capabilities the descriptor declares — the .torrent bytes come over
 * the wire from the real Jackett /dl/ link, the magnet from the real route field.
 */
class IPTorrentsDownload @Inject constructor(
    private val api: IPTorrentsJackettApi,
    private val cache: IPTorrentsResultCache,
) : DownloadableTracker {

    /**
     * Fetches the .torrent bytes for [id] via the cached Jackett /dl/ link. The
     * id MUST come from a prior search (the route's GUID); the link is the
     * lava-api-go-proxied download URL for that row.
     */
    override suspend fun downloadTorrentFile(id: String): ByteArray {
        val downloadUrl = cache.downloadUrlFor(id)
            ?: error(
                "No cached .torrent download URL for IPTorrents id '$id' — run a search first so the " +
                    "/jackett/search route supplies the Jackett /dl/ link.",
            )
        return api.downloadBytes(downloadUrl)
    }

    /** Returns the magnet URI a prior search row carried for [id], or null. */
    override fun getMagnetLink(id: String): String? = cache.magnetFor(id)
}
