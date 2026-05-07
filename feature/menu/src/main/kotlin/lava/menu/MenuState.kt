package lava.menu

import androidx.compose.ui.graphics.Color
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme

internal data class MenuState(
    val theme: Theme = Theme.SYSTEM,
    val favoritesSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val bookmarksSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val historySyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val credentialsSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val activeProviders: List<ProviderMenuItem> = emptyList(),
)

data class ProviderMenuItem(
    val providerId: String,
    val displayName: String,
    val username: String?,
    val isAuthenticated: Boolean,
    val color: Color,
)
