package lava.menu

import lava.models.settings.SyncPeriod
import lava.models.settings.Theme

internal data class MenuState(
    val theme: Theme = Theme.SYSTEM,
    val favoritesSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val bookmarksSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val historySyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val credentialsSyncPeriod: SyncPeriod = SyncPeriod.OFF,
)
