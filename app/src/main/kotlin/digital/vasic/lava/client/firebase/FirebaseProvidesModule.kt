package digital.vasic.lava.client.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lava.common.analytics.AnalyticsTracker
import javax.inject.Singleton

/**
 * Hilt graph for Firebase SDKs + the AnalyticsTracker abstraction.
 *
 * Hardened 2026-05-05 (post-§6.O incident): every @Provides wraps the
 * Firebase SDK accessor in runCatching so a Firebase init failure does
 * NOT cascade to the ViewModel construction site. Without this, a
 * feature ViewModel that injects AnalyticsTracker would crash on first
 * inject if FirebaseInitProvider's auto-init ran into trouble.
 *
 * The AnalyticsTracker @Provides delivers EITHER the real
 * FirebaseAnalyticsTracker (when at least one of Crashlytics/Analytics
 * is available) OR NoOpAnalyticsTracker (when both are null) — never
 * crashes the consumer.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object FirebaseProvidesModule {
    private const val TAG = "FirebaseProvides"

    @Provides
    @Singleton
    fun analytics(@ApplicationContext context: Context): FirebaseAnalytics? =
        runCatching { FirebaseAnalytics.getInstance(context) }
            .onFailure { Log.w(TAG, "FirebaseAnalytics.getInstance failed", it) }
            .getOrNull()

    @Provides
    @Singleton
    fun crashlytics(): FirebaseCrashlytics? =
        runCatching { FirebaseCrashlytics.getInstance() }
            .onFailure { Log.w(TAG, "FirebaseCrashlytics.getInstance failed", it) }
            .getOrNull()

    @Provides
    @Singleton
    fun performance(): FirebasePerformance? =
        runCatching { FirebasePerformance.getInstance() }
            .onFailure { Log.w(TAG, "FirebasePerformance.getInstance failed", it) }
            .getOrNull()

    @Provides
    @Singleton
    fun analyticsTracker(
        analytics: FirebaseAnalytics?,
        crashlytics: FirebaseCrashlytics?,
    ): AnalyticsTracker {
        if (analytics == null && crashlytics == null) {
            Log.w(TAG, "Both Firebase SDKs unavailable — using NoOpAnalyticsTracker")
            return NoOpAnalyticsTracker
        }
        return FirebaseAnalyticsTracker(analytics, crashlytics)
    }
}
