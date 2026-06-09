package digital.vasic.lava.client.crash

import lava.common.analytics.AnalyticsTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LVA-008 §6.AC instrumentation tests.
 *
 * Falsifiability (§6.T.1 / Sixth Law clause 2): break [NavTeardownCrashReporter.isKnownNavTeardownCrash]
 * (e.g. make it always return false) and [knownNavTeardownCrash_isTaggedThenDelegated] FAILS with
 * "expected the known LVA-008 crash to be recorded" — the test cannot pass while the detection is broken.
 */
class NavTeardownCrashReporterTest {

    /** Records every recordNonFatal/setProperty call so the test asserts the §6.AC context. */
    private class RecordingAnalytics : AnalyticsTracker {
        data class NonFatal(val throwable: Throwable, val context: Map<String, String>)

        val nonFatals = mutableListOf<NonFatal>()
        val properties = mutableMapOf<String, String?>()

        override fun event(name: String, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
        override fun setProperty(key: String, value: String?) {
            properties[key] = value
        }
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
            nonFatals += NonFatal(throwable, context)
        }
        override fun recordWarning(message: String, context: Map<String, String>) = Unit
        override fun log(message: String) = Unit
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var delegated: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            delegated = e
        }
    }

    /** The exact production crash: CREATED→DESTROYED ISE on an androidx-navigation frame. */
    private fun navTeardownIse(): IllegalStateException {
        val e = IllegalStateException(
            "Cannot move to state DESTROYED. State must be at least 'CREATED' to be moved to 'DESTROYED'.",
        )
        e.stackTrace = arrayOf(
            StackTraceElement("androidx.navigation.NavBackStackEntry", "handleLifecycleEvent", "NavBackStackEntry.kt", 213),
            StackTraceElement("androidx.navigation.NavControllerImpl", "moveToDestroyed", "NavControllerImpl.kt", 142),
        )
        return e
    }

    @Test
    fun `detects the LVA-008 nav-teardown crash`() {
        assertTrue(NavTeardownCrashReporter.isKnownNavTeardownCrash(navTeardownIse()))
    }

    @Test
    fun `detects it through a cause chain`() {
        val wrapped = RuntimeException("activity destroy failed", navTeardownIse())
        assertTrue(NavTeardownCrashReporter.isKnownNavTeardownCrash(wrapped))
    }

    @Test
    fun `does NOT match an ISE with the message but no nav frame`() {
        val e = IllegalStateException("State must be at least 'CREATED' to be moved to 'DESTROYED'.")
        e.stackTrace = arrayOf(
            StackTraceElement("lava.feature.search.SearchViewModel", "doStuff", "SearchViewModel.kt", 10),
        )
        assertFalse(NavTeardownCrashReporter.isKnownNavTeardownCrash(e))
    }

    @Test
    fun `does NOT match an unrelated exception`() {
        val e = RuntimeException("network timeout")
        e.stackTrace = arrayOf(StackTraceElement("okhttp3.RealCall", "execute", "RealCall.kt", 1))
        assertFalse(NavTeardownCrashReporter.isKnownNavTeardownCrash(e))
    }

    @Test
    fun `known nav-teardown crash is tagged then delegated`() {
        val analytics = RecordingAnalytics()
        val previous = RecordingHandler()
        val reporter = NavTeardownCrashReporter(analytics, previous)
        val crash = navTeardownIse()

        reporter.uncaughtException(Thread.currentThread(), crash)

        // §6.AC: the fatal was recorded with attributable context BEFORE process death.
        assertEquals(1, analytics.nonFatals.size)
        val ctx = analytics.nonFatals.single().context
        assertEquals("navigation", ctx[AnalyticsTracker.Params.FEATURE])
        assertEquals("activity-teardown", ctx[AnalyticsTracker.Params.OPERATION])
        assertEquals("search_input", ctx[AnalyticsTracker.Params.SCREEN])
        assertEquals("LVA-008", ctx[NavTeardownCrashReporter.LVA_ID_KEY])
        assertEquals("true", analytics.properties[NavTeardownCrashReporter.KNOWN_DEFECT_KEY])
        // It is NEVER swallowed — the previous handler still receives the crash.
        assertEquals(crash, previous.delegated)
    }

    @Test
    fun `unrelated crash is NOT tagged but still delegated`() {
        val analytics = RecordingAnalytics()
        val previous = RecordingHandler()
        val reporter = NavTeardownCrashReporter(analytics, previous)
        val crash = RuntimeException("network timeout")

        reporter.uncaughtException(Thread.currentThread(), crash)

        assertTrue(analytics.nonFatals.isEmpty())
        assertNull(analytics.properties[NavTeardownCrashReporter.KNOWN_DEFECT_KEY])
        // Still delegated — the reporter never drops a crash it doesn't recognise.
        assertEquals(crash, previous.delegated)
    }
}
