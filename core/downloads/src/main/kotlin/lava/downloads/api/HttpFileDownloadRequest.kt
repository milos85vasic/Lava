package lava.downloads.api

/**
 * LVA-052 — request to persist an already-fetched HTTP artifact to the public
 * Downloads collection.
 *
 * @property id provider-specific artifact id (used only for telemetry tagging).
 * @property fileName the suggested filename for the saved artifact (must be
 *   non-blank; the impl sanitises it for the target filesystem).
 * @property bytes the raw file content to write (the impl rejects empty bytes).
 */
data class HttpFileDownloadRequest(
    val id: String,
    val fileName: String,
    val bytes: ByteArray,
) {
    // ByteArray needs structural equals/hashCode for value-semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpFileDownloadRequest) return false
        return id == other.id &&
            fileName == other.fileName &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
