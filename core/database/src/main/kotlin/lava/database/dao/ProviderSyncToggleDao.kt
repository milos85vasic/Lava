package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ProviderSyncToggleEntity

@Dao
interface ProviderSyncToggleDao {
    @Query("SELECT * FROM provider_sync_toggle WHERE providerId = :providerId")
    fun observe(providerId: String): Flow<ProviderSyncToggleEntity?>

    /**
     * Sweep Finding #10 closure (2026-05-17, §6.L 59th invocation).
     *
     * Synchronous read used by [lava.provider.config.ProviderConfigViewModel]
     * to compute the next toggle value from the persisted source-of-truth
     * rather than `state.syncEnabled` — which can be stale during the
     * pre-`observeAll()`-emission race window. Pre-fix: a first tap that
     * landed before observeAll() emitted the persisted `true` would
     * compute `!false = true` and overwrite the persisted `true` with
     * an unchanged `true` on a fresh provider (harmless) OR with a
     * deceptive `false` on a reconfigured one (silently flips the toggle
     * back). With this accessor the toggle is deterministic.
     */
    @Query("SELECT * FROM provider_sync_toggle WHERE providerId = :providerId")
    suspend fun get(providerId: String): ProviderSyncToggleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderSyncToggleEntity)
}
