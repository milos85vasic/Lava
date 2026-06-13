package lava.analytics.firebase

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import lava.common.analytics.AnalyticsTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring proof for the shared :core:analytics-firebase module that BOTH
 * :app and :api-app consume (Decoupled Reusable Architecture). Before this
 * module existed, the Firebase impl + DI were app-private to :app and the
 * :api-app had NO AnalyticsTracker binding at all (the §6.AC/§6.O gap).
 *
 * This test exercises the SAME @Provides factory the Hilt graph in both apps
 * invokes ([FirebaseProvidesModule.analyticsTracker]) and proves:
 *   1. when a real Crashlytics sink is available, the factory returns the
 *      Firebase-backed [FirebaseAnalyticsTracker] (NOT the no-op) — so the
 *      api-app's injected AnalyticsTracker actually reports telemetry;
 *   2. a non-fatal recorded through that resolved tracker reaches the
 *      Crashlytics sink via recordException — i.e. the wiring carries a real
 *      throwable end-to-end, not a swallowed call.
 *
 * §6.J: the SUT is the REAL FirebaseProvidesModule + the REAL
 * FirebaseAnalyticsTracker it constructs. Only the FirebaseCrashlytics SDK
 * (an external boundary, the actual network/IPC sink) is a mock — exactly the
 * "mock only below the SUT" rule.
 *
 * FALSIFIABILITY REHEARSAL (recorded in the Bluff-Audit commit stamp):
 *   Mutation: in FirebaseProvidesModule.analyticsTracker, change the guard to
 *     `return NoOpAnalyticsTracker` unconditionally (drop the real-impl branch).
 *   Observed-Failure: `resolvesToFirebaseBackedTrackerAndForwardsNonFatal`
 *     FAILS at `assertTrue("...FirebaseAnalyticsTracker...", tracker is
 *     FirebaseAnalyticsTracker)` — the no-op is returned, and the subsequent
 *     `verify { crashlytics.recordException(thrown) }` finds zero invocations.
 *   Reverted: yes.
 */
class FirebaseProvidesModuleTest {

    @Test
    fun resolvesToFirebaseBackedTrackerAndForwardsNonFatal() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val analytics = mockk<FirebaseAnalytics>(relaxed = true)
        val errSlot = slot<Throwable>()

        // The EXACT factory both apps' Hilt graphs invoke for the
        // AnalyticsTracker binding.
        val tracker: AnalyticsTracker =
            FirebaseProvidesModule.analyticsTracker(analytics, crashlytics)

        // (1) The binding must resolve to the real Firebase-backed impl, not
        // the silent no-op — otherwise telemetry never leaves the device.
        assertTrue(
            "AnalyticsTracker binding must resolve to FirebaseAnalyticsTracker " +
                "when a Crashlytics sink is available (got ${tracker::class.simpleName})",
            tracker is FirebaseAnalyticsTracker,
        )

        // (2) A non-fatal recorded through the resolved tracker reaches the
        // Crashlytics sink carrying the real throwable.
        val thrown = IllegalStateException("api-embed scrape failed")
        tracker.recordNonFatal(thrown, mapOf("feature" to "api-embed"))

        verify(exactly = 1) { crashlytics.recordException(capture(errSlot)) }
        assertSame(
            "The recorded non-fatal must be the same throwable the caller passed",
            thrown,
            errSlot.captured,
        )
    }

    @Test
    fun fallsBackToNoOpWhenBothSdksUnavailable() {
        // Defensive branch: if Firebase auto-init failed and both accessors
        // returned null, the binding must NOT crash the consumer — it returns
        // the logging no-op instead.
        val tracker = FirebaseProvidesModule.analyticsTracker(analytics = null, crashlytics = null)
        assertEquals(NoOpAnalyticsTracker, tracker)
    }
}
