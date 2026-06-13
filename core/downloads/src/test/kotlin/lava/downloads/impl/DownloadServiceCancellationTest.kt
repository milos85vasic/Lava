package lava.downloads.impl

import android.content.Context
import android.os.Environment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.downloads.api.HttpFileDownloadRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Crashlytics non-fatal `7df61fdba64f9928b067624d6db395ca`
 * (`kotlinx.coroutines.JobCancellationException — "StandaloneCoroutine was
 * cancelled"`, 8 events / 1 user / 1.2.21).
 *
 * Root cause class: a broad `catch (t: Throwable)` in a `suspend` production
 * path recorded NORMAL coroutine cancellation as a non-fatal, polluting the
 * Crashlytics dashboard with false telemetry. The fix is to re-throw
 * [CancellationException] (via [lava.common.analytics.rethrowIfCancellation])
 * as the FIRST line of every such catch, BEFORE any `recordNonFatal`.
 *
 * This test exercises the REAL [DownloadServiceImpl.downloadHttpFile] catch
 * block — the actual offending production code path. It drives the write path
 * to throw a [CancellationException] (the exact signal a cleared
 * `viewModelScope` / a download abandoned because the user left the screen
 * produces mid-write) and asserts the USER-VISIBLE-EQUIVALENT outcome:
 *
 *  - the cancellation PROPAGATES (the coroutine is allowed to stop cooperatively
 *    instead of being swallowed into a `null` return), AND
 *  - ZERO non-fatals are recorded for the cancellation — i.e. the Crashlytics
 *    dashboard stays clean. The recorded-non-fatal COUNT is the measurable
 *    proxy for "the dashboard the operator looks at is not polluted by normal
 *    teardown".
 *
 * Only the outermost Android boundary ([Context] / the `Environment` static the
 * write path resolves the Downloads dir through) is faked — permitted under the
 * Second Law. The SUT ([DownloadServiceImpl]) and its cancellation-handling
 * decision are the real production classes.
 *
 * Bluff-Audit: DownloadServiceCancellationTest
 *   Mutation: in [DownloadServiceImpl.downloadHttpFile], delete the
 *     `t.rethrowIfCancellation()` line at the top of the `catch (t: Throwable)`
 *     block (i.e. restore the pre-fix broad-catch that swallows + records
 *     cancellation).
 *   Observed-Failure: `java.lang.AssertionError: cancellation must propagate,
 *     not be swallowed into a null return` (DownloadServiceCancellationTest.kt:87)
 *     — the mutated broad catch swallowed the CancellationException and returned
 *     null instead of letting it propagate, so the `fail(...)` after the SUT call
 *     fired. (The cancellation was also recorded as a non-fatal — the exact
 *     dashboard pollution of issue 7df61fdb — which the second assertion guards.)
 *   Reverted: yes
 */
class DownloadServiceCancellationTest {

    /** Recording fake for the only faked telemetry boundary — counts records. */
    private class RecordingAnalytics : AnalyticsTracker {
        val nonFatals = mutableListOf<Throwable>()
        val warnings = mutableListOf<String>()

        override fun event(name: String, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
        override fun setProperty(key: String, value: String?) = Unit
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
            nonFatals.add(throwable)
        }

        override fun recordWarning(message: String, context: Map<String, String>) {
            warnings.add(message)
        }

        override fun log(message: String) = Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(Environment::class)
    }

    @Test
    fun `cancellation during http write is rethrown and never recorded as non-fatal`() = runTest {
        val analytics = RecordingAnalytics()
        val context = mockk<Context>(relaxed = true)

        // Under unit-test defaults Build.VERSION.SDK_INT is 0, so downloadHttpFile
        // takes the pre-Q writeToPublicDownloads branch, which resolves the
        // public Downloads dir via Environment. Make that boundary throw the SAME
        // control-flow signal a cancelled coroutine surfaces mid-write (scope
        // cleared / user left the screen). Only this outermost Android boundary
        // is faked — the SUT is the real DownloadServiceImpl.
        mockkStatic(Environment::class)
        every {
            Environment.getExternalStoragePublicDirectory(any())
        } throws CancellationException("scope cleared mid-write")

        val service = DownloadServiceImpl(context, analytics)

        val request = HttpFileDownloadRequest(
            id = "topic-7df61fdb",
            fileName = "payload.torrent",
            bytes = byteArrayOf(1, 2, 3, 4),
        )

        var propagated = false
        try {
            service.downloadHttpFile(request)
            fail("cancellation must propagate, not be swallowed into a null return")
        } catch (e: CancellationException) {
            propagated = true
        }

        assertEquals(
            "cancellation must propagate out of downloadHttpFile (cooperative cancellation honoured)",
            true,
            propagated,
        )
        assertEquals(
            "normal coroutine cancellation MUST NOT be recorded as a non-fatal (issue 7df61fdb dashboard noise)",
            0,
            analytics.nonFatals.size,
        )
    }
}
