package lava.data.impl.repository

import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onStart
import lava.common.SingleItemMutableSharedFlow
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.securestorage.PreferencesStorage
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(
    private val preferencesStorage: PreferencesStorage,
) : SettingsRepository {
    private val mutableSettings = SingleItemMutableSharedFlow<Settings>()

    override suspend fun getSettings() = preferencesStorage.getSettings()

    override fun observeSettings() = mutableSettings
        .asSharedFlow()
        .onStart { emit(getSettings()) }

    override suspend fun setTheme(theme: Theme) {
        updateSettings { copy(theme = theme) }
    }

    override suspend fun setEndpoint(endpoint: Endpoint) {
        updateSettings { copy(endpoint = endpoint) }
    }

    override suspend fun setFavoritesSyncPeriod(syncPeriod: SyncPeriod) {
        updateSettings { copy(favoritesSyncPeriod = syncPeriod) }
    }

    override suspend fun setBookmarksSyncPeriod(syncPeriod: SyncPeriod) {
        updateSettings { copy(bookmarksSyncPeriod = syncPeriod) }
    }

    override suspend fun setHistorySyncPeriod(syncPeriod: SyncPeriod) {
        updateSettings { copy(historySyncPeriod = syncPeriod) }
    }

    override suspend fun setCredentialsSyncPeriod(syncPeriod: SyncPeriod) {
        updateSettings { copy(credentialsSyncPeriod = syncPeriod) }
    }

    private suspend fun updateSettings(update: Settings.() -> Settings) {
        val settings = preferencesStorage
            .getSettings()
            .let(update)
        preferencesStorage.saveSettings(settings)
        mutableSettings.emit(settings)
    }
}
