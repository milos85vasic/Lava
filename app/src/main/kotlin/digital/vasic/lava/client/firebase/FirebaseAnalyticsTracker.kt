package digital.vasic.lava.client.firebase

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import lava.common.analytics.AnalyticsTracker

/**
 * Real-stack AnalyticsTracker. Hardened 2026-05-05 (post-§6.O cycle):
 * accepts NULLABLE SDK clients so a Firebase init failure does not
 * crash the ViewModel that holds this tracker. Each method runCatch-
 * guards the SDK call so a downstream failure (e.g. service-disabled
 * IPC error after collection toggle) becomes a logcat warning instead
 * of a propagated throw.
 *
 * Constructed by [FirebaseProvidesModule.analyticsTracker]; if both
 * SDK clients are null the module installs [NoOpAnalyticsTracker]
 * instead of constructing this class.
 */
internal class FirebaseAnalyticsTracker(
    private val analytics: FirebaseAnalytics?,
    private val crashlytics: FirebaseCrashlytics?,
) : AnalyticsTracker {
    override fun event(name: String, params: Map<String, String>) {
        runCatching {
            val a = analytics ?: return@runCatching
            val bundle = Bundle()
            params.forEach { (key, value) -> bundle.putString(key, value) }
            a.logEvent(name, bundle)
        }.onFailure { Log.w(TAG, "logEvent($name) failed", it) }
        runCatching { crashlytics?.log("event=$name params=$params") }
            .onFailure { Log.w(TAG, "crashlytics.log failed", it) }
    }

    override fun setUserId(userId: String?) {
        runCatching { analytics?.setUserId(userId) }
            .onFailure { Log.w(TAG, "analytics.setUserId failed", it) }
        runCatching { crashlytics?.setUserId(userId.orEmpty()) }
            .onFailure { Log.w(TAG, "crashlytics.setUserId failed", it) }
    }

    override fun setProperty(key: String, value: String?) {
        runCatching { analytics?.setUserProperty(key, value) }
            .onFailure { Log.w(TAG, "analytics.setUserProperty failed", it) }
        runCatching { crashlytics?.setCustomKey(key, value.orEmpty()) }
            .onFailure { Log.w(TAG, "crashlytics.setCustomKey failed", it) }
    }

    override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
        runCatching {
            val c = crashlytics ?: return@runCatching
            context.forEach { (key, value) -> c.setCustomKey(key, value) }
            c.recordException(throwable)
        }.onFailure { Log.w(TAG, "recordNonFatal failed", it) }
    }

    override fun log(message: String) {
        runCatching { crashlytics?.log(message) }
            .onFailure { Log.w(TAG, "crashlytics.log failed", it) }
    }

    companion object {
        private const val TAG = "AnalyticsTracker"
    }
}
