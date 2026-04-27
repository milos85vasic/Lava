package lava.domain.usecase

import lava.data.api.repository.SettingsRepository
import lava.dispatchers.api.Dispatchers
import lava.models.settings.SyncPeriod
import lava.work.api.BackgroundService
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SetBookmarksSyncPeriodUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backgroundService: BackgroundService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(syncPeriod: SyncPeriod) {
        withContext(dispatchers.default) {
            settingsRepository.setBookmarksSyncPeriod(syncPeriod)
            backgroundService.syncBookmarks(syncPeriod)
        }
    }
}
