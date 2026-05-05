package digital.vasic.lava.client

import android.app.Application
import android.os.StrictMode
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.perf.ktx.performance
import dagger.hilt.android.HiltAndroidApp
import lava.network.api.ImageLoader
import lava.tracker.client.work.MirrorHealthCheckWorker
import javax.inject.Inject

@HiltAndroidApp
class LavaApplication : Application() {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workManager: WorkManager

    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        super.onCreate()
        FirebaseApp.initializeApp(this)
        Firebase.crashlytics.apply {
            isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            setCustomKey("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            setCustomKey("version_name", BuildConfig.VERSION_NAME)
            setCustomKey("version_code", BuildConfig.VERSION_CODE)
            setCustomKey("application_id", BuildConfig.APPLICATION_ID)
        }
        Firebase.analytics.apply {
            setAnalyticsCollectionEnabled(!BuildConfig.DEBUG)
            setUserProperty("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            setUserProperty("app_version", BuildConfig.VERSION_NAME)
        }
        Firebase.performance.isPerformanceCollectionEnabled = !BuildConfig.DEBUG
        imageLoader.setup()
        MirrorHealthCheckWorker.schedule(workManager)
    }
}
