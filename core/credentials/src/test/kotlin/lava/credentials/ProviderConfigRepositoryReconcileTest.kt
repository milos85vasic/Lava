package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import lava.database.dao.ProviderConfigDao
import lava.database.entity.ProviderConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduce-first Challenge for the 2026-06-26 operator report:
 * "provider filters seems not to follow available configured providers".
 *
 * Root cause: provider_config row creation is purely ADDITIVE (onboarding
 * [ProviderConfigRepository.ensureDefault], the Provider Config screen, and
 * [ProviderConfigRepository.setUseAnonymous] all create rows with the default
 * `isEnabled = true`), and NOTHING ever disabled them. So the search filter
 * chips — built from `observeAll().filter { searchEnabled && isEnabled }` —
 * showed the UNION of every provider ever onboarded/opened, not the user's
 * current selection.
 *
 * The SUT is the REAL [ProviderConfigRepository] over an in-memory
 * [ProviderConfigDao] that enforces the same primary-key upsert semantics as
 * Room. Only the storage boundary is faked.
 *
 * ## Bluff-Audit
 * See commit body for the mutation + observed-failure record.
 */
class ProviderConfigRepositoryReconcileTest {

    private class InMemoryProviderConfigDao : ProviderConfigDao {
        private val rows = MutableStateFlow<List<ProviderConfigEntity>>(emptyList())
        override suspend fun load(providerId: String): ProviderConfigEntity? =
            rows.value.firstOrNull { it.providerId == providerId }
        override fun observeAll(): Flow<List<ProviderConfigEntity>> = rows
        override fun observe(providerId: String): Flow<ProviderConfigEntity?> =
            rows.map { list -> list.firstOrNull { it.providerId == providerId } }
        override suspend fun upsert(entity: ProviderConfigEntity) {
            rows.value = rows.value.filterNot { it.providerId == entity.providerId } + entity
        }
        override suspend fun delete(providerId: String) {
            rows.value = rows.value.filterNot { it.providerId == providerId }
        }
    }

    private fun row(id: String) = ProviderConfigEntity(
        providerId = id,
        preferredMirrorUrl = null,
        sortPreference = null,
        updatedAt = 0L,
    )

    // CHALLENGE — primary assertion on the search-active provider set that the
    // chip bar renders (observeAll filtered by searchEnabled && isEnabled).
    @Test
    fun keepOnlySearchEnabled_disables_providers_not_in_the_selected_set() = runBlocking {
        val dao = InMemoryProviderConfigDao()
        // Simulate the accumulated state: the user previously onboarded /
        // opened three providers, so all three have enabled rows.
        dao.upsert(row("rutracker"))
        dao.upsert(row("torrentdownloads"))
        dao.upsert(row("yts"))
        val repo = ProviderConfigRepository(dao)

        // The user re-onboards selecting ONLY rutracker.
        repo.keepOnlySearchEnabled(setOf("rutracker"))

        val searchActive = repo.observeAll().first()
            .filter { it.searchEnabled && it.isEnabled }
            .map { it.providerId }
            .sorted()

        assertEquals(
            "the search filter set must equal the user's current selection (rutracker only), " +
                "not the union of every provider ever configured",
            listOf("rutracker"),
            searchActive,
        )
    }
}
