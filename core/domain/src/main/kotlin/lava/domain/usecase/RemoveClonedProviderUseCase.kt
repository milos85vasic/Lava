package lava.domain.usecase

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lava.database.dao.ClonedProviderDao
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import javax.inject.Inject

/**
 * SP-4 Phase G.2 (2026-05-13). Removes a user-cloned provider via
 * soft-delete: stamps `deletedAt = NOW` on the row and enqueues a
 * `CLONED_PROVIDER` outbox event with `deleted = true` so Phase E's
 * uploader can propagate the removal to other devices.
 *
 * Companion to [CloneProviderUseCase] — the SDK's
 * `listAvailableTrackers()` returns only clones with `deletedAt IS NULL`
 * (Phase G.1 DAO filter), so a soft-deleted clone disappears from
 * Menu + provider pickers immediately, matching the user's removal
 * intent.
 */
class RemoveClonedProviderUseCase @Inject constructor(
    private val dao: ClonedProviderDao,
    private val outbox: SyncOutbox,
) {
    @Serializable
    private data class WireRemoval(
        val syntheticId: String,
        val deletedAt: Long,
        val deleted: Boolean = true,
    )

    suspend operator fun invoke(syntheticId: String) {
        val now = System.currentTimeMillis()
        dao.softDelete(syntheticId, now)
        outbox.enqueue(
            SyncOutboxKind.CLONED_PROVIDER,
            json.encodeToString(WireRemoval(syntheticId = syntheticId, deletedAt = now)),
        )
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            // SP-4 Phase G.2: emit default-value fields so the `deleted: true`
            // flag survives JSON round-trip into the outbox payload. Phase E's
            // API handler reads this flag to route upsert vs removal — same
            // pattern as CredentialsEntryRepositoryImpl.
            encodeDefaults = true
        }
    }
}
