package lava.downloads.impl

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE
import android.app.DownloadManager.COLUMN_LOCAL_URI
import android.app.DownloadManager.COLUMN_STATUS
import android.app.DownloadManager.EXTRA_DOWNLOAD_ID
import android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
import android.app.DownloadManager.STATUS_SUCCESSFUL
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Environment.DIRECTORY_DOWNLOADS
import android.os.StrictMode
import android.provider.MediaStore
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import lava.common.analytics.AnalyticsTracker
import lava.common.analytics.rethrowIfCancellation
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.downloads.api.HttpFileDownloadRequest
import java.io.File
import java.net.URI
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DownloadServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analytics: AnalyticsTracker,
) : DownloadService {
    private val cache = DownloadUriCache()
    private val guard = TorrentDownloadGuard(analytics)

    override suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String? {
        val cachedUri = cache.get(downloadRequest.id)
        if (cachedUri != null && File(URI.create(cachedUri)).exists()) {
            // Re-validate the cached file: a previously-cached payload may have
            // been written before this guard existed, or the on-disk bytes may
            // have changed. A cached-but-corrupt file must not be reused.
            return if (isValidTorrentFile(downloadRequest, cachedUri)) {
                cachedUri
            } else {
                cache.remove(downloadRequest.id)
                null
            }
        } else {
            return suspendCoroutine { continuation ->
                context.getSystemService<DownloadManager>()?.let { downloadManager ->
                    val fileName = buildValidFatFilename(downloadRequest.title.plus(".torrent"))
                    val uri = Uri.parse(downloadRequest.uri)
                    val request = DownloadManager.Request(uri).apply {
                        downloadRequest.headers.forEach { (key, value) ->
                            addRequestHeader(key, value)
                        }
                        setDestinationInExternalPublicDir(DIRECTORY_DOWNLOADS, fileName)
                        setTitle(downloadRequest.title)
                        setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    }
                    val downloadId = runCatching { downloadManager.enqueue(request) }.getOrNull()
                    if (downloadId == null) {
                        continuation.resume(null)
                    } else {
                        context.registerDownloadCompleteReceiver(downloadId) {
                            val fileUri = allowDiskReads {
                                downloadManager.getDownloadedFileUri(downloadId)
                            }
                            if (fileUri == null) {
                                continuation.resume(null)
                            } else if (isValidTorrentFile(downloadRequest, fileUri)) {
                                cache.put(downloadRequest.id, fileUri)
                                continuation.resume(fileUri)
                            } else {
                                // Corrupt / HTML-error / truncated .torrent: discard the
                                // file and report failure rather than handing the user a
                                // file no BitTorrent client can open. The guard has already
                                // recorded the §6.AC non-fatal telemetry.
                                allowDiskReads { deleteFileQuietly(fileUri) }
                                continuation.resume(null)
                            }
                        }
                    }
                }
            }
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun Context.registerDownloadCompleteReceiver(
        downloadId: Long,
        onDownloadCompleted: () -> Unit,
    ) {
        val receiver = DownloadCompleteReceiver(downloadId, onDownloadCompleted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                receiver,
                IntentFilter(ACTION_DOWNLOAD_COMPLETE),
                RECEIVER_EXPORTED,
            )
        } else {
            registerReceiver(
                receiver,
                IntentFilter(ACTION_DOWNLOAD_COMPLETE),
            )
        }
    }

    private class DownloadCompleteReceiver(
        private val downloadId: Long,
        private val onDownloadCompleted: () -> Unit,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == ACTION_DOWNLOAD_COMPLETE) {
                val completedId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, 0)
                if (completedId == downloadId) {
                    context.unregisterReceiver(this)
                    onDownloadCompleted()
                }
            }
        }
    }

    private fun DownloadManager.getDownloadedFileUri(downloadId: Long): String? = runCatching {
        query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusColumnIndex = cursor.getColumnIndexOrThrow(COLUMN_STATUS)
                val status = cursor.getInt(statusColumnIndex)
                if (STATUS_SUCCESSFUL == status) {
                    val uriColumnIndex = cursor.getColumnIndexOrThrow(COLUMN_LOCAL_URI)
                    cursor.getString(uriColumnIndex)
                } else {
                    null
                }
            } else {
                null
            }
        }
    }.getOrNull()

    /**
     * Reads the downloaded `.torrent` bytes at [fileUri] and runs the real
     * [TorrentDownloadGuard] over them. Returns `true` only when the bytes are a
     * genuinely-valid `.torrent`. If the file cannot be read, treats it as
     * invalid (a download we cannot read is not a download we can trust) and
     * records a §6.AC non-fatal.
     */
    private fun isValidTorrentFile(downloadRequest: DownloadRequest, fileUri: String): Boolean {
        val bytes = allowDiskReads { readFileBytesQuietly(fileUri) }
        if (bytes == null) {
            analytics.recordWarning(
                "downloaded .torrent could not be read for validation",
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "read_downloaded_torrent",
                    AnalyticsTracker.Params.ERROR_CLASS to "UnreadableTorrentFile",
                    AnalyticsTracker.Params.TOPIC_ID to downloadRequest.id,
                ),
            )
            return false
        }
        return guard.verifyValid(downloadRequest.id, downloadRequest.title, bytes)
    }

    private fun readFileBytesQuietly(fileUri: String): ByteArray? = runCatching {
        File(URI.create(fileUri)).readBytes()
    }.getOrNull()

    private fun deleteFileQuietly(fileUri: String) {
        runCatching { File(URI.create(fileUri)).delete() }
    }

    private fun <T> allowDiskReads(block: () -> T): T {
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }
}
