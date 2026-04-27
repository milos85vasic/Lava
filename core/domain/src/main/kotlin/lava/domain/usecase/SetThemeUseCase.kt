package lava.domain.usecase

import lava.data.api.repository.SettingsRepository
import lava.models.settings.Theme
import javax.inject.Inject

class SetThemeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(theme: Theme) {
        settingsRepository.setTheme(theme)
    }
}
