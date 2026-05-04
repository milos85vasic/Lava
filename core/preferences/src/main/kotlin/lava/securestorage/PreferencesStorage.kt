package lava.securestorage

import lava.models.settings.Settings
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
}
