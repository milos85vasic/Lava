package lava.network.api

/**
 * LVA-052 — Lava-domain seam for fetching a provider's HTTP-served downloadable
 * artifact (e-book / media file) for providers that serve plain HTTP files
 * (Internet Archive, Project Gutenberg) rather than `.torrent` / magnet
 * artifacts.
 *
 * This is the decoupling boundary between `core:domain` (which depends only on
 * `core:network:api`) and the Tracker SDK that actually performs the fetch via
 * `HttpDownloadableTracker.downloadHttpFile`. The implementation lives in
 * `core:network:impl` and delegates to `LavaTrackerSdk.downloadHttpFile`,
 * mirroring the existing [NetworkApi] / `SwitchingNetworkApi` pattern so
 * `core:domain` never imports `core:tracker:*`.
 *
 * Capability Honesty (clause 6.E): [downloadHttpFile] returns null when the
 * provider does NOT declare `HTTP_DOWNLOAD` (its
 * `getFeature(HttpDownloadableTracker)` resolves null) or when the underlying
 * fetch throws. Null is an honest "this provider has no HTTP-download surface /
 * the fetch failed" signal — never a fabricated artifact.
 */
interface HttpDownloadSource {
    /**
     * Fetches the HTTP artifact identified by [id] from the provider
     * [trackerId]. The meaning of [id] is provider-specific (archive.org
     * expects `"identifier/filename"`; gutenberg expects a numeric book id).
     *
     * @return the bytes + source metadata, or null when the provider does not
     *   support HTTP download or the fetch fails.
     */
    suspend fun downloadHttpFile(trackerId: String, id: String): HttpArtifact?
}

/**
 * The artifact produced by [HttpDownloadSource.downloadHttpFile] — the raw
 * bytes plus the source URL + a suggested filename for saving to disk.
 *
 * This is the `core:network:api`-side mirror of the SDK's `HttpDownloadResult`
 * so `core:domain` never imports `core:tracker:*`.
 *
 * @property bytes the raw file content (non-empty on a successful fetch).
 * @property sourceUrl the resolved URL the bytes were fetched from.
 * @property fileName a suggested filename for saving the artifact (never blank).
 */
data class HttpArtifact(
    val bytes: ByteArray,
    val sourceUrl: String,
    val fileName: String,
) {
    // ByteArray needs structural equals/hashCode for value-semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpArtifact) return false
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
