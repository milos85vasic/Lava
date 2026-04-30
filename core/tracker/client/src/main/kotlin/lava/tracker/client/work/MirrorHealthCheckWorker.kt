package lava.tracker.client.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import lava.tracker.client.LavaTrackerSdk
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that probes every known mirror for every
 * registered tracker every 15 minutes (the WorkManager periodic minimum)
 * and persists the result through [LavaTrackerSdk.probeMirrorsFor].
 *
 * Constraints: requires CONNECTED network. No charging requirement so the
 * probes run on cellular if needed (the user expects mirror health to be
 * accurate even when the device is unplugged).
 *
 * Added in SP-3a Phase 4 (Task 4.4).
 */
@HiltWorker
class MirrorHealthCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sdk: LavaTrackerSdk,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        var anyFailed = false
        for (descriptor in sdk.listAvailableTrackers()) {
            try {
                sdk.probeMirrorsFor(descriptor.trackerId)
            } catch (t: Throwable) {
                // Per-tracker failure is recoverable: keep going so one bad
                // tracker doesn't block the others. We retry the whole worker
                // only when EVERY tracker probe failed.
                anyFailed = true
            }
        }
        return if (anyFailed && sdk.listAvailableTrackers().size == 1) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "lava.tracker.mirror-health-check"

        /** Build the periodic request used by [schedule]. Public for tests. */
        fun buildRequest() = PeriodicWorkRequestBuilder<MirrorHealthCheckWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        /**
         * Idempotently registers the periodic worker. Safe to call from
         * Application.onCreate(): WorkManager rejects duplicate uniques
         * automatically when [ExistingPeriodicWorkPolicy.KEEP] is used.
         */
        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                buildRequest(),
            )
        }

        const val INTERVAL_MINUTES: Long = 15L
    }
}
