package digital.vasic.lava.client.firebase

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException
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
        // §6.AC: filter benign structured-concurrency cancellations.
        // Crashlytics issue 7df61fdba64f9928b067624d6db395ca (8 events,
        // 1 user, 1.2.21) was JobCancellationException noise from
        // viewModelScope teardown — not a real failure mode worth
        // surfacing in the non-fatal feed.
        if (throwable.isCancellationOrWraps()) {
            Log.d(TAG, "recordNonFatal skipped: cancellation throwable=${throwable::class.simpleName} ctx=$context")
            return
        }
        runCatching {
            val c = crashlytics ?: return@runCatching
            context.forEach { (key, value) -> c.setCustomKey(key, value.take(MAX_VALUE_CHARS)) }
            c.recordException(throwable)
        }.onFailure { Log.w(TAG, "recordNonFatal failed", it) }
    }

    /**
     * True if this throwable is a [CancellationException] OR has one in
     * its cause chain. Cancellations are structured-concurrency teardown
     * signals, NOT real failure modes — Crashlytics issue
     * `7df61fdba64f9928b067624d6db395ca` was 8 such events from
     * `viewModelScope.launch { ... }` cancellation during ViewModel
     * onCleared. Filtering here keeps the non-fatal feed signal-rich.
     */
    private fun Throwable.isCancellationOrWraps(): Boolean {
        var t: Throwable? = this
        var depth = 0
        while (t != null && depth < 32) {
            if (t is CancellationException) return true
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * §6.AC: surface a non-throwable warning to the Crashlytics non-fatal
     * feed by recording a synthetic [LavaNonFatalWarning] exception with
     * the warning's message + caller-supplied context. The synthetic
     * exception's stack trace points at this method's call site (caller's
     * frame), which Crashlytics groups by package + file + line — so
     * warnings from different sites get separate issues, while repeated
     * occurrences from the same site aggregate naturally.
     */
    override fun recordWarning(message: String, context: Map<String, String>) {
        runCatching {
            val c = crashlytics ?: return@runCatching
            // Add the message to the breadcrumb log first so it's visible
            // even if the synthetic-exception record fails.
            c.log("WARN: ${message.take(MAX_VALUE_CHARS)} ctx=$context")
            context.forEach { (key, value) -> c.setCustomKey(key, value.take(MAX_VALUE_CHARS)) }
            c.recordException(LavaNonFatalWarning(message))
        }.onFailure { Log.w(TAG, "recordWarning failed", it) }
    }

    override fun log(message: String) {
        runCatching { crashlytics?.log(message) }
            .onFailure { Log.w(TAG, "crashlytics.log failed", it) }
    }

    companion object {
        private const val TAG = "AnalyticsTracker"

        // Crashlytics custom-key values are capped at 1024 bytes per the
        // Firebase docs; we also cap warning messages to keep breadcrumbs
        // manageable. Truncation here means the call site MUST front-load
        // the most diagnostic information at the start of the message.
        private const val MAX_VALUE_CHARS = 1024
    }
}

/**
 * Synthetic exception used by [FirebaseAnalyticsTracker.recordWarning]
 * to surface non-throwable warnings into Crashlytics's non-fatal feed.
 * The class name appears in Crashlytics issue titles, so subclass
 * grouping is preserved while the message is the warning text.
 */
internal class LavaNonFatalWarning(message: String) : RuntimeException(message)
