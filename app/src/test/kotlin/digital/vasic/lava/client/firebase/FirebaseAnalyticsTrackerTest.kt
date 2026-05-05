package digital.vasic.lava.client.firebase

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Validation test for the nullable-SDK path of FirebaseAnalyticsTracker
 * (added 2026-05-05 post-§6.O hardening). The Hilt @Provides for the
 * Firebase SDKs returns NULL when getInstance() throws — the tracker
 * MUST tolerate this without propagating to the consumer.
 *
 * Falsifiability rehearsal: remove a `?.` null-safe call from
 * FirebaseAnalyticsTracker.event() — `null.logEvent(...)` becomes a
 * NullPointerException and the corresponding test below fails.
 */
class FirebaseAnalyticsTrackerTest {

    @Test
    fun `event survives both SDKs being null`() {
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = null)
        // Must not throw.
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
        // Must not throw despite SDK throwing.
        tracker.event("any", emptyMap())
    }

    @Test
    fun `event forwards to analytics when present`() {
        val analytics = mockk<FirebaseAnalytics>(relaxed = true)
        val tracker = FirebaseAnalyticsTracker(analytics = analytics, crashlytics = null)
        tracker.event("test_event", mapOf("k" to "v"))
        verify { analytics.logEvent("test_event", any()) }
    }

    @Test
    fun `recordNonFatal forwards to crashlytics when present`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val tracker = FirebaseAnalyticsTracker(analytics = null, crashlytics = crashlytics)
        val err = RuntimeException("test-err")
        tracker.recordNonFatal(err, mapOf("k" to "v"))
        verify { crashlytics.setCustomKey("k", "v") }
        verify { crashlytics.recordException(err) }
    }
}
