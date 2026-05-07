package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.SettingsRepository
import lava.dispatchers.api.Dispatchers
import lava.models.settings.SyncPeriod
import lava.work.api.BackgroundService
import javax.inject.Inject

class SetCredentialsSyncPeriodUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backgroundService: BackgroundService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(syncPeriod: SyncPeriod) {
        withContext(dispatchers.default) {
            settingsRepository.setCredentialsSyncPeriod(syncPeriod)
            backgroundService.syncCredentials(syncPeriod)
        }
    }
}
