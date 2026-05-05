package digital.vasic.lava.client.firebase

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance

/**
 * Resilient Firebase initializer extracted from LavaApplication so the
 * init logic is unit-testable on the JVM (no Application context, no
 * Robolectric needed). Each SDK setup block is independently guarded:
 * a throw from one Firebase service does NOT prevent the others from
 * initializing, and never propagates to the caller.
 *
 * 2026-05-05 forensic anchor: 2 Crashlytics-recorded crashes within
 * minutes of the first Firebase-instrumented APK distribution. The
 * post-mortem is at .lava-ci-evidence/crashlytics-resolved/2026-05-05-firebase-init-hardening.md.
 *
 * Constitutional bindings:
 *   §6.O Crashlytics-Resolved Issue Coverage Mandate — every line of
 *        this file is covered by FirebaseInitializerTest.
 *   §6.J Anti-Bluff — failures are LOGGED, not swallowed; the warn
 *        callback gives the test a primary assertion target on
 *        user-visible (well, operator-visible) state.
 */
internal object FirebaseInitializer {
    fun initialize(
        crashlytics: () -> FirebaseCrashlytics?,
        analytics: () -> FirebaseAnalytics?,
        performance: () -> FirebasePerformance?,
        isDebug: Boolean,
        versionName: String,
        versionCode: Int,
        applicationId: String,
        warn: (String, Throwable) -> Unit,
    ) {
        runCatching {
            crashlytics()?.apply {
                setCrashlyticsCollectionEnabled(!isDebug)
                setCustomKey("build_type", if (isDebug) "debug" else "release")
                setCustomKey("version_name", versionName)
                setCustomKey("version_code", versionCode)
                setCustomKey("application_id", applicationId)
            }
        }.onFailure { warn("Crashlytics init failed", it) }

        runCatching {
            analytics()?.apply {
                setAnalyticsCollectionEnabled(!isDebug)
                setUserProperty("build_type", if (isDebug) "debug" else "release")
                setUserProperty("app_version", versionName)
            }
        }.onFailure { warn("Analytics init failed", it) }

        runCatching {
            performance()?.isPerformanceCollectionEnabled = !isDebug
        }.onFailure { warn("Performance init failed", it) }
    }
}
