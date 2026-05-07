package lava.models.settings

data class Settings(
    // SP-3.2 (2026-04-29): default endpoint is the rutracker direct
    // connection; `Endpoint.Proxy` (public lava-app.tech) was removed
    // from the model entirely per the user mandate.
    val endpoint: Endpoint = Endpoint.Rutracker,
    val theme: Theme = Theme.SYSTEM,
    val favoritesSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val bookmarksSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val historySyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val credentialsSyncPeriod: SyncPeriod = SyncPeriod.OFF,
    val deviceId: String = "",
)
