package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.downloads.api.DownloadService
import lava.downloads.api.HttpFileDownloadRequest
import lava.network.api.HttpDownloadSource
import javax.inject.Inject

/**
 * LVA-052 — downloads an HTTP-served artifact (e-book / media file) from a
 * provider that declares `HTTP_DOWNLOAD` (Internet Archive, Project Gutenberg)
 * and writes it to the user's public Downloads collection.
 *
 * Flow: [HttpDownloadSource] (real SDK fetch over HTTP, Capability-Honest —
 * null when the provider lacks `HTTP_DOWNLOAD` or the fetch fails) →
 * [DownloadService.downloadHttpFile] (persists the bytes to disk). Returns the
 * saved file URI string on success, or null when the provider has no
 * HTTP-download surface, the fetch failed, or the write failed.
 *
 * This is the HTTP-file sibling of [DownloadTorrentUseCase]; the `.torrent`
 * path is untouched.
 */
class DownloadHttpFileUseCase @Inject constructor(
    private val httpDownloadSource: HttpDownloadSource,
    private val downloadService: DownloadService,
    private val dispatchers: Dispatchers,
) {
    /**
     * @param trackerId the provider whose artifact is being fetched.
     * @param id provider-specific artifact id (archive.org: "identifier/filename";
     *   gutenberg: numeric book id).
     * @return the saved file URI string, or null on any failure.
     */
    suspend operator fun invoke(trackerId: String, id: String): String? {
        return withContext(dispatchers.default) {
            val artifact = httpDownloadSource.downloadHttpFile(trackerId, id) ?: return@withContext null
            downloadService.downloadHttpFile(
                HttpFileDownloadRequest(
                    id = id,
                    fileName = artifact.fileName,
                    bytes = artifact.bytes,
                ),
            )
        }
    }
}
