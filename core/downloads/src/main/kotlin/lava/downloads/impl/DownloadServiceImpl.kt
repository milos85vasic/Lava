package lava.downloads.impl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Environment.DIRECTORY_DOWNLOADS
import android.os.StrictMode
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import lava.common.analytics.AnalyticsTracker
import lava.common.analytics.rethrowIfCancellation
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.downloads.api.HttpFileDownloadRequest
import java.io.File
import java.net.URI
import javax.inject.Inject

class DownloadServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analytics: AnalyticsTracker,
) : DownloadService {
    private val cache = DownloadUriCache()
    private val guard = TorrentDownloadGuard(analytics)

    /**
     * Persists an already-fetched `.torrent` to the public Downloads collection.
     *
     * 2026-07-03 reroute: the bytes are fetched IN-APP by [DownloadTorrentUseCase]
     * over the app's trusted OkHttp client and arrive here in [DownloadRequest.bytes]
     * — this method no longer enqueues an OS `DownloadManager` URL fetch (which ran
     * over the SYSTEM trust store and rejected the goapi's self-signed cert, so the
     * download never completed and LAN self-signed-proxy users could never download
     * `.torrent` files). The bytes are validated as genuine bencode BEFORE writing
     * (§6.J/§6.E — never hand the user a file no BitTorrent client can open) and
     * then written to the public Downloads collection via the SAME trusted path as
     * [downloadHttpFile], so a BitTorrent client can open the saved file.
     */
    override suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String? {
        // Cache: a valid .torrent already saved for this id is returned as-is. If
        // the cached file is missing / unreadable / no longer valid it is dropped
        // and re-fetched below (a redundant re-write, never a wrong result).
        cache.get(downloadRequest.id)?.let { cachedUri ->
            if (isValidTorrentFile(downloadRequest, cachedUri)) {
                return cachedUri
            }
            cache.remove(downloadRequest.id)
        }

        // §6.AC: reject an empty artifact before writing (mirror downloadHttpFile) —
        // an empty body is a fetch/parse defect upstream, never a saved 0-byte file.
        if (downloadRequest.bytes.isEmpty()) {
            analytics.recordWarning(
                "torrent download produced empty bytes; nothing written",
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "download_torrent_file",
                    AnalyticsTracker.Params.ERROR_CLASS to "EmptyTorrentArtifact",
                    AnalyticsTracker.Params.TOPIC_ID to downloadRequest.id,
                ),
            )
            return null
        }

        // §6.J/§6.E: validate the bencode BEFORE persisting — a Cloudflare HTML
        // interstitial or a truncated body saved under a `.torrent` name is a
        // "successful download the user cannot open". The guard records its own
        // §6.AC non-fatal on rejection.
        if (!guard.verifyValid(downloadRequest.id, downloadRequest.title, downloadRequest.bytes)) {
            return null
        }

        val fileName = buildValidFatFilename(downloadRequest.title.plus(".torrent"))
        return try {
            val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(fileName, downloadRequest.bytes)
            } else {
                writeToPublicDownloads(fileName, downloadRequest.bytes)
            }
            if (savedUri != null) {
                cache.put(downloadRequest.id, savedUri)
            }
            savedUri
        } catch (t: Throwable) {
            // Cooperative cancellation (scope cleared / user left mid-write) is
            // control flow, not a download failure — re-throw BEFORE any non-fatal
            // so it never pollutes Crashlytics (issue 7df61fdb).
            t.rethrowIfCancellation()
            analytics.recordNonFatal(
                t,
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "download_torrent_file",
                    AnalyticsTracker.Params.ERROR_CLASS to (t::class.simpleName ?: "TorrentDownloadWriteError"),
                    AnalyticsTracker.Params.TOPIC_ID to downloadRequest.id,
                ),
            )
            null
        }
    }

    override suspend fun downloadHttpFile(downloadRequest: HttpFileDownloadRequest): String? {
        if (downloadRequest.bytes.isEmpty()) {
            // §6.AC: an empty artifact is a fetch/parse defect upstream — record
            // it rather than silently writing a 0-byte file the user can't open.
            analytics.recordWarning(
                "HTTP download produced empty bytes; nothing written",
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "download_http_file",
                    AnalyticsTracker.Params.ERROR_CLASS to "EmptyHttpArtifact",
                    AnalyticsTracker.Params.TOPIC_ID to downloadRequest.id,
                ),
            )
            return null
        }
        val fileName = buildValidFatFilename(downloadRequest.fileName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(fileName, downloadRequest.bytes)
            } else {
                writeToPublicDownloads(fileName, downloadRequest.bytes)
            }
        } catch (t: Throwable) {
            // Normal coroutine cancellation (scope cleared / user left the screen
            // mid-write) is control flow, NOT a download failure. Re-throw it
            // BEFORE recording any non-fatal so it never pollutes Crashlytics
            // (issue 7df61fdb) and cooperative cancellation is honoured.
            t.rethrowIfCancellation()
            analytics.recordNonFatal(
                t,
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "download_http_file",
                    AnalyticsTracker.Params.ERROR_CLASS to (t::class.simpleName ?: "HttpDownloadWriteError"),
                    AnalyticsTracker.Params.TOPIC_ID to downloadRequest.id,
                ),
            )
            null
        }
    }

    /**
     * API 29+ scoped-storage path: insert into the public Downloads MediaStore
     * collection and stream the bytes into the returned content URI. Returns the
     * content URI string the system assigns (the user-visible saved location).
     */
    private fun writeViaMediaStore(fileName: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(itemUri)?.use { it.write(bytes) } ?: run {
            resolver.delete(itemUri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        return itemUri.toString()
    }

    /**
     * Pre-API-29 path: write to the public external Downloads directory.
     * Returns the file URI string of the written file.
     */
    private fun writeToPublicDownloads(fileName: String, bytes: ByteArray): String? = allowDiskReads {
        val dir = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return@allowDiskReads null
        val target = File(dir, fileName)
        target.writeBytes(bytes)
        Uri.fromFile(target).toString()
    }

    /**
     * Reads the saved `.torrent` bytes at [fileUri] and runs the real
     * [TorrentDownloadGuard] over them. Returns `true` only when the bytes are a
     * genuinely-valid `.torrent`. If the file cannot be read, treats it as invalid
     * (a file we cannot read is not one we can trust) and records a §6.AC non-fatal.
     */
    private fun isValidTorrentFile(downloadRequest: DownloadRequest, fileUri: String): Boolean {
        val bytes = allowDiskReads { readFileBytesQuietly(fileUri) }
        if (bytes == null) {
            analytics.recordWarning(
                "saved .torrent could not be read for validation",
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "read_saved_torrent",
                    AnalyticsTracker.Params.ERROR_CLASS to "UnreadableTorrentFile",
                    AnalyticsTracker.Params.TOPIC_ID to downloadRequest.id,
                ),
            )
            return false
        }
        return guard.verifyValid(downloadRequest.id, downloadRequest.title, bytes)
    }

    /**
     * Reads the bytes at [fileUri], handling BOTH the `content://` URIs
     * [writeViaMediaStore] returns on API 29+ and the `file://` URIs
     * [writeToPublicDownloads] returns pre-29. Returns null (treated as invalid)
     * on any read failure.
     */
    private fun readFileBytesQuietly(fileUri: String): ByteArray? = runCatching {
        val uri = Uri.parse(fileUri)
        if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } else {
            File(URI.create(fileUri)).readBytes()
        }
    }.getOrNull()

    private fun <T> allowDiskReads(block: () -> T): T {
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }
}
