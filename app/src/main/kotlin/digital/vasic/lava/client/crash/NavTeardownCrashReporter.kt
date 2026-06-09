package digital.vasic.lava.client.crash

import lava.common.analytics.AnalyticsTracker

/**
 * LVA-008 — §6.AC instrumentation for the accepted upstream nav-teardown crash.
 *
 * The `search_input` `NavBackStackEntry` teardown `IllegalStateException`
 * ("State must be at least 'CREATED' to be moved to 'DESTROYED'") is a
 * device-falsified UPSTREAM androidx-navigation defect — five fix avenues were
 * exhausted and reverted (forensics:
 * `.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json`).
 *
 * Per the 2026-06-09 operator decision the crash is ACCEPTED for distribution
 * WITH §6.AC telemetry. This reporter tags the fatal with attributable context
 * BEFORE the process dies, so it surfaces in Crashlytics as the KNOWN LVA-008
 * defect (filterable, triageable) rather than an unattributed mystery crash.
 *
 * It does NOT swallow the crash — the process still dies via the chained default
 * handler. It only instruments it. Swallowing a fatal would be a different, worse
 * bug; the §6.AC mandate is observability, not suppression.
 */
class NavTeardownCrashReporter(
    private val analytics: AnalyticsTracker,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (isKnownNavTeardownCrash(throwable)) {
            // Best-effort: a failure to record telemetry must never change the
            // crash semantics. The process is dying either way.
            runCatching {
                analytics.setProperty(KNOWN_DEFECT_KEY, "true")
                analytics.recordNonFatal(
                    throwable,
                    mapOf(
                        AnalyticsTracker.Params.FEATURE to FEATURE_NAVIGATION,
                        AnalyticsTracker.Params.OPERATION to OPERATION_TEARDOWN,
                        AnalyticsTracker.Params.ERROR_CLASS to (throwable::class.java.simpleName.ifBlank { "IllegalStateException" }),
                        AnalyticsTracker.Params.ERROR_MESSAGE to throwable.message.orEmpty().take(MAX_MESSAGE),
                        AnalyticsTracker.Params.SCREEN to SCREEN_SEARCH_INPUT,
                        KNOWN_DEFECT_KEY to "true",
                        LVA_ID_KEY to LVA_ID,
                    ),
                )
            }
        }
        // Never swallow — chain to the previous handler so the process dies as before.
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        const val KNOWN_DEFECT_KEY = "lva_008_known_upstream_nav_teardown"
        const val LVA_ID_KEY = "lva_id"
        const val LVA_ID = "LVA-008"

        private const val FEATURE_NAVIGATION = "navigation"
        private const val OPERATION_TEARDOWN = "activity-teardown"
        private const val SCREEN_SEARCH_INPUT = "search_input"
        private const val MAX_MESSAGE = 1024
        private const val MAX_CAUSE_DEPTH = 10

        /**
         * Install the reporter as the default uncaught-exception handler,
         * chaining whatever handler is currently installed. Idempotent — a
         * second call (or a re-install after [Thread.setDefaultUncaughtExceptionHandler])
         * does not double-wrap.
         */
        fun install(analytics: AnalyticsTracker) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is NavTeardownCrashReporter) return
            Thread.setDefaultUncaughtExceptionHandler(NavTeardownCrashReporter(analytics, current))
        }

        /**
         * Pure detection: is [t] (or any cause in its chain) the LVA-008
         * nav-teardown ISE? Matches an [IllegalStateException] whose message names
         * the CREATED→DESTROYED lifecycle transition AND whose stack involves
         * androidx-navigation — both conditions are required so an unrelated ISE
         * that merely mentions "DESTROYED" is not mis-tagged.
         */
        fun isKnownNavTeardownCrash(t: Throwable?): Boolean {
            var cur = t
            var depth = 0
            while (cur != null && depth < MAX_CAUSE_DEPTH) {
                if (cur is IllegalStateException) {
                    val msg = cur.message.orEmpty()
                    val lifecycleMatch = msg.contains("must be at least", ignoreCase = true) &&
                        msg.contains("DESTROYED", ignoreCase = true)
                    if (lifecycleMatch && hasNavFrame(cur)) return true
                }
                cur = cur.cause
                depth++
            }
            return false
        }

        private fun hasNavFrame(t: Throwable): Boolean =
            t.stackTrace.any { frame ->
                val cn = frame.className
                cn.contains("androidx.navigation", ignoreCase = true) ||
                    cn.contains("NavController", ignoreCase = true) ||
                    cn.contains("NavBackStackEntry", ignoreCase = true)
            }
    }
}
