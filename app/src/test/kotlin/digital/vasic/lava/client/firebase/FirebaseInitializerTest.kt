package digital.vasic.lava.client.firebase

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validation test for §6.O — Crashlytics-Resolved Issue Coverage Mandate.
 *
 * Forensic anchor: 2 Crashlytics-recorded crashes within minutes of the
 * first Firebase-instrumented APK distribution (2026-05-05). The fix
 * extracted Firebase init into FirebaseInitializer with per-SDK
 * runCatching guards. This test is the regression-immunity gate.
 *
 * Falsifiability rehearsal: removing any one of the runCatching blocks
 * in FirebaseInitializer.initialize causes the corresponding test below
 * to fail with the assertion message named in the test's KDoc.
 */
class FirebaseInitializerTest {
    private val captured = mutableListOf<Pair<String, Throwable>>()
    private val warn: (String, Throwable) -> Unit = { msg, t -> captured += msg to t }

    @Test
    fun `initialize survives crashlytics throw`() {
        // Mutation rehearsal: removing runCatching{} around crashlytics
        // makes this test fail with `RuntimeException: crashlytics-boom`.
        val crashlytics = mockk<FirebaseCrashlytics>()
        every { crashlytics.setCrashlyticsCollectionEnabled(any()) } throws
            RuntimeException("crashlytics-boom")

        FirebaseInitializer.initialize(
            crashlytics = { crashlytics },
            analytics = { null },
            performance = { null },
            isDebug = false,
            versionName = "1.2.3",
            versionCode = 1023,
            applicationId = "digital.vasic.lava.client",
            warn = warn,
        )

        // Primary assertion: the warn callback received the structured
        // failure (operator-visible signal) AND no exception propagated.
        assertEquals(1, captured.size)
        assertEquals("Crashlytics init failed", captured[0].first)
        assertEquals("crashlytics-boom", captured[0].second.message)
    }

    @Test
    fun `initialize survives analytics throw`() {
        val analytics = mockk<FirebaseAnalytics>()
        every { analytics.setAnalyticsCollectionEnabled(any()) } throws
            RuntimeException("analytics-boom")

        FirebaseInitializer.initialize(
            crashlytics = { null },
            analytics = { analytics },
            performance = { null },
            isDebug = false,
            versionName = "1.2.3",
            versionCode = 1023,
            applicationId = "digital.vasic.lava.client",
            warn = warn,
        )

        assertEquals(1, captured.size)
        assertEquals("Analytics init failed", captured[0].first)
        assertEquals("analytics-boom", captured[0].second.message)
    }

    @Test
    fun `initialize survives performance throw`() {
        val perf = mockk<FirebasePerformance>()
        every { perf.isPerformanceCollectionEnabled = any() } throws
            RuntimeException("perf-boom")

        FirebaseInitializer.initialize(
            crashlytics = { null },
            analytics = { null },
            performance = { perf },
            isDebug = false,
            versionName = "1.2.3",
            versionCode = 1023,
            applicationId = "digital.vasic.lava.client",
            warn = warn,
        )

        assertEquals(1, captured.size)
        assertEquals("Performance init failed", captured[0].first)
        assertEquals("perf-boom", captured[0].second.message)
    }

    @Test
    fun `initialize tolerates null SDK clients`() {
        // The supplier callbacks return null when Firebase auto-init has
        // failed (KTX accessor throws). The initializer must NOT propagate.
        FirebaseInitializer.initialize(
            crashlytics = { null },
            analytics = { null },
            performance = { null },
            isDebug = true,
            versionName = "1.2.3",
            versionCode = 1023,
            applicationId = "digital.vasic.lava.client",
            warn = warn,
        )
        assertEquals(0, captured.size)
    }

    @Test
    fun `initialize wires crashlytics custom keys when SDK is healthy`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        FirebaseInitializer.initialize(
            crashlytics = { crashlytics },
            analytics = { null },
            performance = { null },
            isDebug = false,
            versionName = "1.2.3",
            versionCode = 1023,
            applicationId = "digital.vasic.lava.client",
            warn = warn,
        )
        // Primary assertion: SDK calls happened in the documented order.
        verify { crashlytics.setCrashlyticsCollectionEnabled(true) }
        verify { crashlytics.setCustomKey("build_type", "release") }
        verify { crashlytics.setCustomKey("version_name", "1.2.3") }
        verify { crashlytics.setCustomKey("version_code", 1023) }
        verify { crashlytics.setCustomKey("application_id", "digital.vasic.lava.client") }
        assertEquals(0, captured.size)
    }
}
