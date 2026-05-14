package digital.vasic.lava.client.firebase

import android.util.Log
import lava.common.analytics.AnalyticsTracker

/**
 * Fallback AnalyticsTracker used when Firebase SDKs are unavailable.
 *
 * Forensic anchor: §6.O Crashlytics-Resolved Issue Coverage Mandate +
 * the post-1.2.4 hardening cycle. A null Firebase SDK at injection
 * time MUST NOT crash the consuming ViewModel — the consumer simply
 * gets a tracker that logs to logcat instead of reporting to Firebase.
 *
 * Tests verify this in app/src/test/.../FirebaseAnalyticsTrackerTest.
 */
internal object NoOpAnalyticsTracker : AnalyticsTracker {
    private const val TAG = "AnalyticsTracker[noop]"

    override fun event(name: String, params: Map<String, String>) {
        Log.d(TAG, "event=$name params=$params")
    }

    override fun setUserId(userId: String?) {
        Log.d(TAG, "setUserId=$userId")
    }

    override fun setProperty(key: String, value: String?) {
        Log.d(TAG, "setProperty $key=$value")
    }

    override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
        Log.w(TAG, "recordNonFatal context=$context", throwable)
    }

    override fun recordWarning(message: String, context: Map<String, String>) {
        Log.w(TAG, "recordWarning $message context=$context")
    }

    override fun log(message: String) {
        Log.d(TAG, message)
    }
}
