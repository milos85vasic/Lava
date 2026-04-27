package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Settings
import javax.inject.Inject

class ObserveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(): Flow<Settings> = settingsRepository.observeSettings()
}
