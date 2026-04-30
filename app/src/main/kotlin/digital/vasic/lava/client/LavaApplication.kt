package digital.vasic.lava.client

import android.app.Application
import android.os.StrictMode
import androidx.work.WorkManager
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
        imageLoader.setup()
        // SP-3a Phase 4 (Task 4.4): schedule the 15-minute periodic mirror
        // health probe. Idempotent — KEEP policy means re-launches don't
        // start fresh schedules.
        MirrorHealthCheckWorker.schedule(workManager)
    }
}
