package lava.downloads.impl

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import lava.common.analytics.AnalyticsTracker
import lava.downloads.api.HttpFileDownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for the user-visible empty-artifact guard in the production
 * [DownloadServiceImpl.downloadHttpFile] path — the path a user reaches when they
 * tap a download whose HTTP body the Tracker SDK fetched as zero bytes (a
 * fetch/parse defect, a Cloudflare 204, a truncated-to-nothing response).
 *
 * §6.D / §6.N finding (2026-06-13): `DownloadServiceImpl` had ZERO direct
 * real-stack coverage — `TorrentDownloadGuardTest` covers the `.torrent`
 * validation gate, and `TopicViewModelHttpDownloadTest` uses a
 * `RecordingDownloadService` FAKE (it never touches the real impl). The
 * empty-bytes branch (lines 96-109) — the decision that turns a 0-byte artifact
 * into a rejected download instead of a saved-but-unopenable 0-byte file — was a
 * COVERAGE GAP: a §6.N mutation that deleted the guard SURVIVED (all tests stayed
 * green). This test closes the gap.
 *
 * The empty-bytes path returns BEFORE any Android API call (no MediaStore, no
 * file write), so it is exercisable in a pure JVM unit test. The SUT is the REAL
 * production [DownloadServiceImpl]; the ONLY faked boundaries are the outermost
 * ones: [AnalyticsTracker] (a recording fake) and Android [Context] (a mockk that
 * is NEVER dereferenced on this path — the empty guard returns first).
 *
 * Primary assertion is on the user-visible outcome: the call returns `null`
 * (nothing was saved to the Downloads collection — the user is NOT handed a
 * broken 0-byte file) AND the §6.AC operator-facing warning is surfaced.
 *
 * Bluff-Audit: DownloadServiceImplHttpEmptyBytesTest
 *   Mutation: in [DownloadServiceImpl.downloadHttpFile], change
 *     `if (downloadRequest.bytes.isEmpty())` to
 *     `if (false && downloadRequest.bytes.isEmpty())` (never reject empty bytes —
 *     fall through to the write path, the exact COVERAGE-GAP state).
 *   Observed-Failure: `empty HTTP artifact must surface a §6.AC warning so the
 *     operator sees the bad download expected:<1> but was:<0>` at
 *     emptyHttpBytesSurfaceWarningAndSaveNothing — and, on the SDK_INT-default
 *     write path, the real impl reaches the Android MediaStore call and the
 *     return value is no longer the guarded null.
 *   Reverted: yes
 */
class DownloadServiceImplHttpEmptyBytesTest {

    /** Recording fake for the only telemetry boundary. */
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

    // Context is the Android boundary; the empty-bytes guard returns before it is
    // ever touched, so a bare mockk with no stubbing is sufficient and proves the
    // path never crosses into Android.
    private val context = mockk<Context>()

    private val service = DownloadServiceImpl(context, analytics)

    @Test
    fun emptyHttpBytesSurfaceWarningAndSaveNothing() = runBlocking {
        val savedUri = service.downloadHttpFile(
            HttpFileDownloadRequest(
                id = "topic-42",
                fileName = "mobydick.epub",
                bytes = ByteArray(0),
            ),
        )

        assertNull(
            "an empty HTTP artifact must NOT be saved — the user must not be handed " +
                "a 0-byte file no reader can open; the call must return null",
            savedUri,
        )
        assertEquals(
            "empty HTTP artifact must surface a §6.AC warning so the operator sees the bad download",
            1,
            analytics.warnings.size,
        )
        assertEquals(
            "the warning must be classified as an empty-artifact defect",
            "EmptyHttpArtifact",
            analytics.warningClasses.single(),
        )
        assertTrue(
            "the warning text must name the empty-bytes condition",
            analytics.warnings.single().contains("empty bytes"),
        )
    }
}
