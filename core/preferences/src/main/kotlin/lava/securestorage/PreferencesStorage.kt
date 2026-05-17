package lava.securestorage

import lava.models.settings.Settings
import lava.models.settings.SyncPeriod
import lava.securestorage.model.Account

/**
 * Storage for secure data management.
 */
interface PreferencesStorage {
    /**
     * Save user account.
     */
    suspend fun saveAccount(account: Account)

    /**
     * Load user account or null
     */
    suspend fun getAccount(): Account?

    /**
     * Clear user account.
     */
    suspend fun clearAccount()

    /**
     * Save user settings.
     */
    suspend fun saveSettings(settings: Settings)

    /**
     * Load user setting or null.
     */
    suspend fun getSettings(): Settings

    suspend fun getRatingLaunchCount(): Int

    suspend fun setRatingLaunchCount(count: Int)

    suspend fun getRatingDisabled(): Boolean

    suspend fun setRatingDisabled(value: Boolean)

    suspend fun getRatingPostponed(): Boolean

    suspend fun setRatingPostponed(value: Boolean)

    suspend fun isOnboardingComplete(): Boolean

    suspend fun setOnboardingComplete(value: Boolean)

    /**
     * Sweep Finding #9 closure (2026-05-17, §6.L 59th invocation).
     *
     * Observable variant of [isOnboardingComplete] so the host Activity
     * can re-render onboarding when the value flips at runtime — e.g.
     * if a future Settings action calls `setOnboardingComplete(false)`
     * the home screen must transition back to the welcome flow without
     * requiring a process restart. Pre-fix `MainActivity.onCreate`
     * read the value exactly once inside `repeatOnLifecycle(STARTED)`
     * and was held there by an infinite `viewModel.theme.collect`.
     *
     * Default: emits the current persisted value (or false when unset),
     * then re-emits on every change via SharedPreferences listener.
     */
    fun observeOnboardingComplete(): kotlinx.coroutines.flow.Flow<Boolean>

    /**
     * Phase 1.1 — persist the multi-tracker session-signaled auth state
     * across process death. Without this, a user who completed an
     * AuthType.NONE provider's onboarding (archive.org Continue tap)
     * would face "Authorization required to search" again after force-
     * quitting the app, because [getAccount] only knows about the
     * legacy single-tracker (RuTracker) account.
     *
     * The persisted shape is a `(name, avatarUrl?)` pair. Returns null
     * when no signal has been persisted (or after [clearSignaledAuthState]).
     *
     * See `.lava-ci-evidence/sixth-law-incidents/2026-05-04-onboarding-
     * navigation.json` (`smaller_followup_persistence_of_active_tracker_choice`).
     */
    suspend fun getSignaledAuthState(): SignaledAuthState?

    suspend fun saveSignaledAuthState(name: String, avatarUrl: String?)

    suspend fun clearSignaledAuthState()

    fun getDeviceId(): String
    fun getHistorySyncPeriod(): SyncPeriod
    fun setHistorySyncPeriod(period: SyncPeriod)
    fun getCredentialsSyncPeriod(): SyncPeriod
    fun setCredentialsSyncPeriod(period: SyncPeriod)
}

/**
 * Persisted shape of the multi-tracker session-signaled auth state.
 * Sealed off from the legacy [Account] model on purpose — the signal
 * is purely an observation-layer record (no token, no password).
 */
data class SignaledAuthState(
    val name: String,
    val avatarUrl: String?,
)
