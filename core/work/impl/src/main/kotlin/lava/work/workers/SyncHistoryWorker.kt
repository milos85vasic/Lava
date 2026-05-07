package lava.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import lava.logger.api.LoggerFactory

@HiltWorker
internal class SyncHistoryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val loggerFactory: LoggerFactory,
) : CoroutineWorker(appContext, workerParams) {
    private val logger = loggerFactory.get("SyncHistoryWorker")

    override suspend fun doWork(): Result {
        logger.d { "History sync worker ran (placeholder)" }
        return Result.success()
    }
}
