package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature

/**
 * Capability-typed feature for providers that serve their downloadable
 * artifact over plain HTTP(S) rather than as a `.torrent` / magnet
 * (clause 6.E Capability Honesty — gated by [lava.tracker.api.TrackerCapability.HTTP_DOWNLOAD]).
 *
 * This is the honest home for providers like Internet Archive (`archiveorg`)
 * and Project Gutenberg (`gutenberg`): they produce e-books / media files
 * (EPUB, plain text, HTML, raw media) — NOT bencoded `.torrent` files. The
 * existing [DownloadableTracker] contract is `.torrent`-shaped
 * (`downloadTorrentFile` + `getMagnetLink`); routing an HTTP file through it
 * would be a 6.E bluff because the consumer would receive a non-`.torrent`
 * artifact through the torrent-download surface. Hence a distinct interface
 * with a distinct capability.
 *
 * The returned [HttpDownloadResult] carries BOTH the raw bytes (the
 * user-visible artifact written to disk) AND the resolved source URL +
 * suggested filename so the consumer can name the saved file correctly.
 */
interface HttpDownloadableTracker : TrackerFeature {
    /**
     * Downloads the file identified by [id] over HTTP(S) and returns the
     * bytes plus source metadata. The meaning of [id] is provider-specific
     * (e.g. archive.org expects `"identifier/filename"`; gutenberg expects a
     * numeric book id and resolves the best available format internally).
     *
     * Implementations MUST throw on a non-2xx response or an empty body —
     * a successful return guarantees a non-empty artifact.
     */
    suspend fun downloadHttpFile(id: String): HttpDownloadResult
}

/**
 * The artifact produced by [HttpDownloadableTracker.downloadHttpFile].
 *
 * @property bytes the raw file content (non-empty on success).
 * @property sourceUrl the resolved URL the bytes were fetched from.
 * @property fileName a suggested filename for saving the artifact (derived
 *   from [sourceUrl]'s last path segment; never blank).
 */
data class HttpDownloadResult(
    val bytes: ByteArray,
    val sourceUrl: String,
    val fileName: String,
) {
    // ByteArray needs structural equals/hashCode for value-semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpDownloadResult) return false
        return bytes.contentEquals(other.bytes) &&
            sourceUrl == other.sourceUrl &&
            fileName == other.fileName
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + sourceUrl.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
