package digital.vasic.lava.client.firebase

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAnalyticsTrackerTest {

    @Test
    fun `event survives both SDKs being null`() {
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = null)
        tracker.event("any", mapOf("k" to "v"))
        tracker.setUserId("u1")
        tracker.setProperty("k", "v")
        tracker.recordNonFatal(RuntimeException("boom"), mapOf("k" to "v"))
        tracker.log("hi")
    }

    @Test
    fun `event survives crashlytics throw`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        every { crashlytics.log(any<String>()) } throws RuntimeException("c-boom")
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = crashlytics)
        tracker.event("any", emptyMap())
    }

    @Test
    fun `event forwards to analytics when present`() {
        val analytics = mockk<FirebaseAnalytics>(relaxed = true)
        val nameSlot = slot<String>()
        val bundleSlot = slot<Bundle>()
        every { analytics.logEvent(capture(nameSlot), capture(bundleSlot)) } answers { }
        val tracker = FirebaseAnalyticsTracker(analytics = analytics, crashlytics = null)
        tracker.event("test_event", mapOf("k" to "v"))
        assertEquals("test_event", nameSlot.captured)
        assertTrue("Bundle must be captured (call happened)", bundleSlot.isCaptured)
    }

    @Test
    fun `recordNonFatal forwards to crashlytics when present`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val keySlot = slot<String>()
        val valSlot = slot<String>()
        val errSlot = slot<Throwable>()
        every { crashlytics.setCustomKey(capture(keySlot), capture(valSlot)) } answers { }
        every { crashlytics.recordException(capture(errSlot)) } answers { }
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = crashlytics)
        val err = RuntimeException("test-err")
        tracker.recordNonFatal(err, mapOf("k" to "v"))
        assertEquals("k", keySlot.captured)
        assertEquals("v", valSlot.captured)
        assertEquals(err, errSlot.captured)
    }

    /**
     * §6.O closure validation for Crashlytics issue
     * `7df61fdba64f9928b067624d6db395ca` (8 events / 1 user / 1.2.21
     * "kotlinx.coroutines.JobCancellationException — StandaloneCoroutine
     * was cancelled"). Cancellation throwables are structured-concurrency
     * teardown noise; they MUST NOT reach Crashlytics's non-fatal feed
     * because they're not real failure modes. The fix: filter them at
     * the FirebaseAnalyticsTracker.recordNonFatal entry point.
     */
    @Test
    fun `recordNonFatal filters CancellationException (Crashlytics 7df61fdb)`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = crashlytics)
        val cancellation = CancellationException("StandaloneCoroutine was cancelled")
        tracker.recordNonFatal(cancellation, mapOf("k" to "v"))
        verify(exactly = 0) { crashlytics.setCustomKey(any<String>(), any<String>()) }
        verify(exactly = 0) { crashlytics.recordException(any<Throwable>()) }
    }

    @Test
    fun `recordNonFatal filters when CancellationException is wrapped`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = crashlytics)
        val wrapped = RuntimeException("outer", CancellationException("inner cancellation"))
        tracker.recordNonFatal(wrapped, emptyMap())
        verify(exactly = 0) { crashlytics.recordException(any<Throwable>()) }
    }

    @Test
    fun `recordNonFatal still reports real exceptions (cancellation filter does not over-filter)`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = crashlytics)
        val real = IllegalArgumentException("a real failure")
        tracker.recordNonFatal(real, emptyMap())
        verify(exactly = 1) { crashlytics.recordException(real) }
    }
}
