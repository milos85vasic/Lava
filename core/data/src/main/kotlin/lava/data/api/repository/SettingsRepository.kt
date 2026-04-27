package lava.data.api.repository

import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    suspend fun getSettings(): Settings
    fun observeSettings(): Flow<Settings>
    suspend fun setTheme(theme: Theme)
    suspend fun setEndpoint(endpoint: Endpoint)
    suspend fun setFavoritesSyncPeriod(syncPeriod: SyncPeriod)
    suspend fun setBookmarksSyncPeriod(syncPeriod: SyncPeriod)
}
