package lava.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import lava.logger.api.LoggerFactory
import lava.tracker.api.model.AuthState
import lava.tracker.client.LavaTrackerSdk

@HiltWorker
internal class SyncCredentialsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sdk: LavaTrackerSdk,
    private val loggerFactory: LoggerFactory,
) : CoroutineWorker(appContext, workerParams) {
    private val logger = loggerFactory.get("SyncCredentialsWorker")

    override suspend fun doWork(): Result {
        logger.d { "Credential sync worker running" }
        val providers = sdk.listAvailableTrackers()
        var failures = 0
        for (desc in providers) {
            try {
                val state = sdk.checkAuth(desc.trackerId)
                if (state !is AuthState.Authenticated) {
                    logger.d { "Credential check failed for ${desc.trackerId}" }
                    failures++
                }
            } catch (e: Exception) {
                logger.e(e) { "Error checking auth for ${desc.trackerId}" }
                failures++
            }
        }
        return if (failures == providers.size) Result.retry() else Result.success()
    }
}
