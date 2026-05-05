package digital.vasic.lava.client.firebase

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lava.common.analytics.AnalyticsTracker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object FirebaseProvidesModule {
    @Provides
    @Singleton
    fun analytics(@ApplicationContext context: Context): FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context)

    @Provides
    @Singleton
    fun crashlytics(): FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

    @Provides
    @Singleton
    fun performance(): FirebasePerformance = FirebasePerformance.getInstance()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface FirebaseBindsModule {
    @Binds
    @Singleton
    fun analyticsTracker(impl: FirebaseAnalyticsTracker): AnalyticsTracker
}
