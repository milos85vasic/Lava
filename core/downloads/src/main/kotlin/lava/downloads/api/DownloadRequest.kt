package lava.downloads.api

/**
 * Request to persist an already-fetched `.torrent` artifact to the public
 * Downloads collection.
 *
 * 2026-07-03 reroute (incident
 * `.lava-ci-evidence/sixth-law-incidents/2026-07-03-rutracker-kinozal-torrent-download-stall.json`):
 * this used to carry a `uri` + `headers` for the OS
 * [android.app.DownloadManager] to fetch. DownloadManager fetches over the SYSTEM
 * trust store and therefore REJECTED the goapi's self-signed cert
 * (CertPathValidatorException → the download never completed for the user, and
 * LAN self-signed-proxy users could NEVER download `.torrent` files). It now
 * carries the raw [bytes] the app already fetched IN-APP over its trusted OkHttp
 * client — mirroring [HttpFileDownloadRequest]; the impl persists them to the
 * public Downloads collection so a BitTorrent client can open them.
 *
 * @property id provider-specific topic/artifact id (cache key + telemetry tag).
 * @property title user-facing title; the saved file is named "<title>.torrent".
 * @property bytes the raw `.torrent` content to persist (the impl rejects empty).
 */
data class DownloadRequest(
    val id: String,
    val title: String,
    val bytes: ByteArray,
) {
    // ByteArray needs structural equals/hashCode for value-semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadRequest) return false
        return id == other.id &&
            title == other.title &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
