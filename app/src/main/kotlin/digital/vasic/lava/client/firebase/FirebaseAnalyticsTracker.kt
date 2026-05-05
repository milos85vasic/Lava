package digital.vasic.lava.client.firebase

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import lava.common.analytics.AnalyticsTracker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAnalyticsTracker
@Inject
constructor(
    private val analytics: FirebaseAnalytics,
    private val crashlytics: FirebaseCrashlytics,
) : AnalyticsTracker {
    override fun event(name: String, params: Map<String, String>) {
        val bundle = Bundle()
        params.forEach { (key, value) -> bundle.putString(key, value) }
        analytics.logEvent(name, bundle)
        crashlytics.log("event=$name params=$params")
    }

    override fun setUserId(userId: String?) {
        analytics.setUserId(userId)
        crashlytics.setUserId(userId.orEmpty())
    }

    override fun setProperty(key: String, value: String?) {
        analytics.setUserProperty(key, value)
        crashlytics.setCustomKey(key, value.orEmpty())
    }

    override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
        context.forEach { (key, value) -> crashlytics.setCustomKey(key, value) }
        crashlytics.recordException(throwable)
    }

    override fun log(message: String) {
        crashlytics.log(message)
    }
}
