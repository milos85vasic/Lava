package lava.securestorage

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.models.settings.Endpoint
import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.securestorage.model.Account
import lava.securestorage.model.EndpointConverter
import lava.securestorage.preferences.SharedPreferencesFactory
import lava.securestorage.utils.clear
import lava.securestorage.utils.edit
import javax.inject.Inject

internal class PreferencesStorageImpl @Inject constructor(
    private val sharedPreferencesFactory: SharedPreferencesFactory,
    private val dispatchers: Dispatchers,
) : PreferencesStorage {
    private val accountPreferences: SharedPreferences by lazy {
        sharedPreferencesFactory.getSharedPreferences("account")
    }
    private val settingsPreferences: SharedPreferences
        get() = sharedPreferencesFactory.getSharedPreferences("settings")
    private val ratingPreferences: SharedPreferences by lazy {
        sharedPreferencesFactory.getSharedPreferences("rating")
    }

    // Phase 1.1 — separate file so the multi-tracker session signal
    // doesn't collide with the legacy single-tracker `accountPreferences`
    // schema.
    private val signaledAuthPreferences: SharedPreferences by lazy {
        sharedPreferencesFactory.getSharedPreferences("signaled_auth")
    }

    override suspend fun saveAccount(account: Account) {
        withContext(dispatchers.io) {
            accountPreferences.edit {
                putString(accountIdKey, account.id)
                putString(accountUsernameKey, account.name)
                putString(accountPasswordKey, account.password)
                putString(accountTokenKey, account.token)
                putString(accountAvatarKey, account.avatarUrl)
            }
        }
    }

    override suspend fun getAccount(): Account? {
        return withContext(dispatchers.io) {
            runCatching {
                val id = accountPreferences.getString(accountIdKey, null)
                val username = accountPreferences.getString(accountUsernameKey, null)
                val token = accountPreferences.getString(accountTokenKey, null)
                val password = accountPreferences.getString(accountPasswordKey, null)
                if (id != null && username != null && token != null && password != null) {
                    Account(
                        id = id,
                        name = username,
                        token = token,
                        password = password,
                        avatarUrl = accountPreferences.getString(accountAvatarKey, null),
                    )
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    override suspend fun clearAccount() {
        withContext(dispatchers.io) {
            accountPreferences.clear()
        }
    }

    override suspend fun saveSettings(settings: Settings) {
        withContext(dispatchers.io) {
            settingsPreferences.edit {
                putString(endpointKey, with(EndpointConverter) { settings.endpoint.toJson() })
                putString(themeKey, settings.theme.name)
                putString(favoritesSyncPeriodKey, settings.favoritesSyncPeriod.name)
                putString(bookmarksSyncPeriodKey, settings.bookmarksSyncPeriod.name)
                putString(historySyncPeriodKey, settings.historySyncPeriod.name)
                putString(credentialsSyncPeriodKey, settings.credentialsSyncPeriod.name)
            }
        }
    }

    override suspend fun getSettings(): Settings {
        return withContext(dispatchers.io) {
            val storedEndpoint = settingsPreferences.getString(endpointKey, null)?.let {
                with(EndpointConverter) { fromJson(it) }
            }
            // Operator directive 2026-05-12: Endpoint.Rutracker (direct
            // rutracker.org) is no longer surfaced. Persisted Rutracker
            // values — including Android Auto Backup restores — are
            // migrated on read to the LAN lava-api-go mDNS placeholder
            // and cleared from prefs.
            val endpoint: Endpoint = if (storedEndpoint == null || storedEndpoint is Endpoint.Rutracker) {
                if (storedEndpoint is Endpoint.Rutracker) {
                    settingsPreferences.edit { remove(endpointKey) }
                }
                Endpoint.GoApi(host = "lava-api.local")
            } else {
                storedEndpoint
            }
            val theme = settingsPreferences.getString(themeKey, null)?.let {
                enumValueOf(it)
            } ?: Theme.SYSTEM
            val favoritesSyncPeriod =
                settingsPreferences.getString(favoritesSyncPeriodKey, null)?.let {
                    enumValueOf(it)
                } ?: SyncPeriod.OFF
            val bookmarksSyncPeriod =
                settingsPreferences.getString(bookmarksSyncPeriodKey, null)?.let {
                    enumValueOf(it)
                } ?: SyncPeriod.OFF
            val historySyncPeriod =
                settingsPreferences.getString(historySyncPeriodKey, null)?.let {
                    enumValueOf(it)
                } ?: SyncPeriod.OFF
            val credentialsSyncPeriod =
                settingsPreferences.getString(credentialsSyncPeriodKey, null)?.let {
                    enumValueOf(it)
                } ?: SyncPeriod.OFF
            Settings(
                endpoint = endpoint,
                theme = theme,
                favoritesSyncPeriod = favoritesSyncPeriod,
                bookmarksSyncPeriod = bookmarksSyncPeriod,
                historySyncPeriod = historySyncPeriod,
                credentialsSyncPeriod = credentialsSyncPeriod,
                deviceId = getDeviceId(),
            )
        }
    }

    override suspend fun getRatingLaunchCount(): Int {
        return withContext(dispatchers.io) {
            ratingPreferences.getInt(ratingLaunchCountKey, 0)
        }
    }

    override suspend fun setRatingLaunchCount(count: Int) {
        withContext(dispatchers.io) {
            ratingPreferences.edit { putInt(ratingLaunchCountKey, count) }
        }
    }

    override suspend fun getRatingDisabled(): Boolean {
        return withContext(dispatchers.io) {
            ratingPreferences.getBoolean(ratingDisabledKey, false)
        }
    }

    override suspend fun setRatingDisabled(value: Boolean) {
        withContext(dispatchers.io) {
            ratingPreferences.edit { putBoolean(ratingDisabledKey, value) }
        }
    }

    override suspend fun getRatingPostponed(): Boolean {
        return withContext(dispatchers.io) {
            ratingPreferences.getBoolean(ratingPostponedKey, false)
        }
    }

    override suspend fun setRatingPostponed(value: Boolean) {
        withContext(dispatchers.io) {
            ratingPreferences.edit { putBoolean(ratingPostponedKey, value) }
        }
    }

    override suspend fun isOnboardingComplete(): Boolean {
        return withContext(dispatchers.io) {
            settingsPreferences.getBoolean(onboardingCompleteKey, false)
        }
    }

    override suspend fun setOnboardingComplete(value: Boolean) {
        withContext(dispatchers.io) {
            settingsPreferences.edit { putBoolean(onboardingCompleteKey, value) }
        }
    }

    /**
     * Sweep Finding #9 closure (2026-05-17, §6.L 59th invocation).
     *
     * SharedPreferences-listener-backed Flow so the host Activity can
     * react to in-process `onboarding_complete` flips without a process
     * restart. The flow:
     *   1. Emits the current persisted value (or false) on subscription
     *      via `onStart` so the first collect terminates the splash
     *      screen exactly like `isOnboardingComplete()` did pre-fix.
     *   2. Re-emits every time the value changes — covered by the
     *      OnSharedPreferenceChangeListener registered on the same
     *      [settingsPreferences] instance.
     *   3. Unregisters the listener on cancellation per the awaitClose
     *      contract.
     *
     * Hot subscribers may transform/distinct as appropriate. The current
     * MainActivity consumer calls `.distinctUntilChanged()` so a redundant
     * emission (same value re-written) does not re-render.
     */
    override fun observeOnboardingComplete(): Flow<Boolean> = callbackFlow {
        val prefs = settingsPreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == onboardingCompleteKey) {
                trySend(prefs.getBoolean(onboardingCompleteKey, false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .onStart { emit(settingsPreferences.getBoolean(onboardingCompleteKey, false)) }
        .flowOn(dispatchers.io)

    override suspend fun getSignaledAuthState(): SignaledAuthState? {
        return withContext(dispatchers.io) {
            val name = signaledAuthPreferences.getString(signaledAuthNameKey, null)
                ?: return@withContext null
            val avatar = signaledAuthPreferences.getString(signaledAuthAvatarKey, null)
            SignaledAuthState(name = name, avatarUrl = avatar)
        }
    }

    override suspend fun saveSignaledAuthState(name: String, avatarUrl: String?) {
        withContext(dispatchers.io) {
            signaledAuthPreferences.edit {
                putString(signaledAuthNameKey, name)
                if (avatarUrl != null) {
                    putString(signaledAuthAvatarKey, avatarUrl)
                } else {
                    remove(signaledAuthAvatarKey)
                }
            }
        }
    }

    override fun getHistorySyncPeriod(): SyncPeriod {
        val stored = settingsPreferences.getString(historySyncPeriodKey, null)
        return stored?.let { enumValueOf<SyncPeriod>(it) } ?: SyncPeriod.OFF
    }

    override fun setHistorySyncPeriod(period: SyncPeriod) {
        settingsPreferences.edit { putString(historySyncPeriodKey, period.name) }
    }

    override fun getCredentialsSyncPeriod(): SyncPeriod {
        val stored = settingsPreferences.getString(credentialsSyncPeriodKey, null)
        return stored?.let { enumValueOf<SyncPeriod>(it) } ?: SyncPeriod.OFF
    }

    override fun setCredentialsSyncPeriod(period: SyncPeriod) {
        settingsPreferences.edit { putString(credentialsSyncPeriodKey, period.name) }
    }

    override suspend fun clearSignaledAuthState() {
        withContext(dispatchers.io) {
            signaledAuthPreferences.clear()
        }
    }

    override fun getDeviceId(): String {
        val existing = settingsPreferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = java.util.UUID.randomUUID().toString()
        settingsPreferences.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    private companion object {
        const val accountIdKey = "account_id"
        const val accountUsernameKey = "account_username"
        const val accountPasswordKey = "account_password"
        const val accountTokenKey = "account_token"
        const val accountAvatarKey = "account_avatar_url"

        const val endpointKey = "endpoint"
        const val themeKey = "theme"
        const val favoritesSyncPeriodKey = "favorites_sync_period"
        const val bookmarksSyncPeriodKey = "bookmarks_sync_period"
        const val historySyncPeriodKey = "history_sync_period"
        const val credentialsSyncPeriodKey = "credentials_sync_period"

        const val ratingLaunchCountKey = "rating_launch_count"
        const val ratingDisabledKey = "rating_disabled"
        const val ratingPostponedKey = "rating_postponed"

        const val onboardingCompleteKey = "onboarding_complete"

        const val signaledAuthNameKey = "signaled_auth_name"
        const val signaledAuthAvatarKey = "signaled_auth_avatar_url"

        const val KEY_DEVICE_ID = "device_id"
    }
}
