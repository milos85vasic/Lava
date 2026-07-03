package lava.downloads.impl

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import lava.common.analytics.AnalyticsTracker
import lava.downloads.api.DownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for the user-visible validate-BEFORE-write gate in the
 * production [DownloadServiceImpl.downloadTorrentFile] path after the 2026-07-03
 * reroute.
 *
 * Context (2026-07-03 incident
 * `.lava-ci-evidence/sixth-law-incidents/2026-07-03-rutracker-kinozal-torrent-download-stall.json`):
 * `.torrent` downloads used to be enqueued to the OS [android.app.DownloadManager],
 * which fetches over the SYSTEM trust store and REJECTED the goapi's self-signed
 * cert → the download hung and never completed for the user. The reroute makes the
 * use case fetch the `.torrent` BYTES in-app (over the app's trusted OkHttp) and
 * hand them here to persist — the same trusted path `downloadHttpFile` uses. As a
 * side effect the bencode [TorrentDownloadGuard] now gates BEFORE the write (the
 * old path validated AFTER DownloadManager had already written the file).
 *
 * Both branches asserted here return BEFORE any Android API call (no MediaStore,
 * no file write), so they are exercisable in a pure JVM unit test. The SUT is the
 * REAL production [DownloadServiceImpl]; the ONLY faked boundaries are the
 * outermost ones: [AnalyticsTracker] (a recording fake) and Android [Context] (a
 * mockk that is NEVER dereferenced on these paths — the guard/empty check returns
 * first). The bencode validator inside the guard is the REAL
 * `lava.common.torrent.TorrentFileValidator`.
 *
 * Primary assertion is on the user-visible outcome: the call returns `null`
 * (nothing was saved to Downloads — the user is NOT handed a file no BitTorrent
 * client can open) AND the §6.AC operator-facing warning is surfaced.
 *
 * Bluff-Audit: DownloadServiceImplTorrentBytesTest
 *   Mutation: in [DownloadServiceImpl.downloadTorrentFile], short-circuit the
 *     bencode gate — `if (false && !guard.verifyValid(...))` — so the invalid
 *     payload is NOT rejected before the write (the "save an unopenable file"
 *     state the reroute closes).
 *   Observed-Failure (run 2026-07-03): `invalidTorrentBytesRejectedBeforeWriteAndSaveNothing`
 *     FAILED — `java.lang.AssertionError: the §6.AC warning must classify the
 *     invalid-torrent rejection so the operator sees it` (the guard never ran, so
 *     no InvalidTorrentPayload warning was recorded; the mockk-Context write then
 *     throws and the catch still returns null, so the discriminating signal is the
 *     ABSENT guard warning — on a real device the invalid HTML would be written to
 *     Downloads as a `.torrent`). `emptyTorrentBytesSurfaceWarningAndSaveNothing`
 *     still PASSED (its empty-bytes guard is independent).
 *   Reverted: yes (re-run BUILD SUCCESSFUL)
 */
class DownloadServiceImplTorrentBytesTest {

    private class RecordingAnalytics : AnalyticsTracker {
        val warnings = mutableListOf<String>()
        val warningClasses = mutableListOf<String>()

        override fun event(name: String, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
        override fun setProperty(key: String, value: String?) = Unit
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) = Unit
        override fun recordWarning(message: String, context: Map<String, String>) {
            warnings.add(message)
            context[AnalyticsTracker.Params.ERROR_CLASS]?.let { warningClasses.add(it) }
        }

        override fun log(message: String) = Unit
    }

    private val analytics = RecordingAnalytics()

    // Context is the Android boundary; the empty/invalid guards return before it is
    // ever touched, so a bare mockk with no stubbing is sufficient and proves these
    // paths never cross into Android (a valid payload's write is covered on-device
    // by Challenge70, matching the existing downloadHttpFile write-path coverage).
    private val context = mockk<Context>()

    private val service = DownloadServiceImpl(context, analytics)

    @Test
    fun emptyTorrentBytesSurfaceWarningAndSaveNothing() = runBlocking {
        val savedUri = service.downloadTorrentFile(
            DownloadRequest(id = "topic-42", title = "Ubuntu ISO", bytes = ByteArray(0)),
        )

        assertNull(
            "an empty .torrent artifact must NOT be saved — the user must not be handed " +
                "a 0-byte file no BitTorrent client can open; the call must return null",
            savedUri,
        )
        assertEquals(
            "empty .torrent must surface a §6.AC warning so the operator sees the bad download",
            1,
            analytics.warnings.size,
        )
        assertTrue(
            "the warning text must name the empty-bytes condition",
            analytics.warnings.single().contains("empty"),
        )
    }

    @Test
    fun invalidTorrentBytesRejectedBeforeWriteAndSaveNothing() = runBlocking {
        // A Cloudflare-style HTML error page saved under a .torrent name — the exact
        // "successful download the user can't open" class the guard exists to reject.
        val htmlError = "<html><body>Access denied</body></html>".toByteArray(Charsets.UTF_8)

        val savedUri = service.downloadTorrentFile(
            DownloadRequest(id = "topic-7", title = "Not A Torrent", bytes = htmlError),
        )

        assertNull(
            "a non-bencode payload must be rejected BEFORE writing — the user must not " +
                "receive an unopenable file; the call must return null",
            savedUri,
        )
        assertTrue(
            "the §6.AC warning must classify the invalid-torrent rejection so the operator sees it",
            analytics.warningClasses.contains("InvalidTorrentPayload"),
        )
    }
}
