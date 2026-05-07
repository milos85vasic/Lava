package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme

class TestSettingsRepository : SettingsRepository {
    private val mutableSettings = MutableStateFlow(Settings())

    override suspend fun getSettings(): Settings {
        return mutableSettings.value
    }

    override fun observeSettings(): Flow<Settings> = mutableSettings.asStateFlow()

    override suspend fun setTheme(theme: Theme) {
        val settings = mutableSettings.value.copy(theme = theme)
        mutableSettings.emit(settings)
    }

    override suspend fun setEndpoint(endpoint: Endpoint) {
        val settings = mutableSettings.value.copy(endpoint = endpoint)
        mutableSettings.emit(settings)
    }

    override suspend fun setFavoritesSyncPeriod(syncPeriod: SyncPeriod) {
        val settings = mutableSettings.value.copy(favoritesSyncPeriod = syncPeriod)
        mutableSettings.emit(settings)
    }

    override suspend fun setBookmarksSyncPeriod(syncPeriod: SyncPeriod) {
        val settings = mutableSettings.value.copy(bookmarksSyncPeriod = syncPeriod)
        mutableSettings.emit(settings)
    }

    override suspend fun setHistorySyncPeriod(syncPeriod: SyncPeriod) {
        val settings = mutableSettings.value.copy(historySyncPeriod = syncPeriod)
        mutableSettings.emit(settings)
    }

    override suspend fun setCredentialsSyncPeriod(syncPeriod: SyncPeriod) {
        val settings = mutableSettings.value.copy(credentialsSyncPeriod = syncPeriod)
        mutableSettings.emit(settings)
    }
}
