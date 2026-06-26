package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import lava.database.dao.ProviderConfigDao
import lava.database.entity.ProviderConfigEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for per-provider configuration (timeouts, mirrors, capability
 * toggles).
 *
 * Added in Multi-Provider Extension (Task 6.5).
 */
@Singleton
class ProviderConfigRepository @Inject constructor(
    private val dao: ProviderConfigDao,
) {

    suspend fun load(providerId: String): ProviderConfig? {
        return dao.load(providerId)?.toModel()
    }

    fun observeAll(): Flow<List<ProviderConfig>> {
        return dao.observeAll().map { list -> list.map { it.toModel() } }
    }

    fun observe(providerId: String): Flow<ProviderConfig?> {
        return dao.observe(providerId).map { it?.toModel() }
    }

    suspend fun save(config: ProviderConfig) {
        dao.upsert(config.toEntity())
    }

    suspend fun ensureDefault(providerId: String): ProviderConfig {
        val existing = dao.load(providerId)
        if (existing != null) {
            return existing.toModel()
        }
        val default = ProviderConfig(providerId = providerId)
        dao.upsert(default.toEntity())
        return default
    }

    /**
     * Sweep Finding #1 closure (2026-05-17, §6.L 59th invocation):
     * single-field mutator so [lava.provider.config.ProviderConfigViewModel]
     * can flip the per-provider anonymous flag without round-tripping the
     * full config. The repository creates a default row if none exists so
     * the toggle works on first-tap even for providers the user never
     * opened a Provider Config page for. The §6.J anti-bluff requirement
     * is met because the persisted row drives [observe] which the VM
     * re-binds to `state.anonymous` — pre-fix the toggle was in-memory only.
     */
    suspend fun setUseAnonymous(providerId: String, useAnonymous: Boolean) {
        val existing = dao.load(providerId)
        val updated = (existing?.toModel() ?: ProviderConfig(providerId = providerId))
            .copy(useAnonymous = useAnonymous, updatedAt = System.currentTimeMillis())
        dao.upsert(updated.toEntity())
    }

    /**
     * 2026-06-26 operator FIX ("provider filters don't follow available
     * configured providers"): reconcile the SEARCH-active provider set to be
     * EXACTLY [selectedIds]. Every existing config row whose providerId is NOT
     * in [selectedIds] is disabled (`isEnabled = false`, `searchEnabled =
     * false`) so it stops appearing in the search filter chips; rows IN
     * [selectedIds] are left untouched (onboarding already [ensureDefault]'d +
     * configured them).
     *
     * Root cause this fixes: row creation is purely ADDITIVE — onboarding's
     * [ensureDefault] (per selected provider), the Provider Config screen, and
     * [setUseAnonymous] all create rows with the default `isEnabled = true`,
     * and NOTHING ever disabled them. So [observeAll] `.filter { searchEnabled
     * && isEnabled }` returned the UNION of every provider ever onboarded or
     * opened, not the user's current selection — the user configures rutracker
     * only, yet the search filter chips show every provider that ever had a
     * row. Reconciling at onboarding-finish makes the chip bar honour the
     * actual selection. Reproduced by
     * ProviderConfigRepositoryReconcileTest.
     */
    suspend fun keepOnlySearchEnabled(selectedIds: Set<String>) {
        val all = dao.observeAll().first()
        for (entity in all) {
            if (entity.providerId !in selectedIds && (entity.isEnabled || entity.searchEnabled)) {
                dao.upsert(
                    entity.copy(
                        isEnabled = false,
                        searchEnabled = false,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun ProviderConfigEntity.toModel(): ProviderConfig {
        return ProviderConfig(
            providerId = providerId,
            timeoutMs = timeoutMs,
            preferredMirrorUrl = preferredMirrorUrl,
            isEnabled = isEnabled,
            searchEnabled = searchEnabled,
            browseEnabled = browseEnabled,
            downloadEnabled = downloadEnabled,
            useAnonymous = useAnonymous,
            sortPreference = sortPreference,
            updatedAt = updatedAt,
        )
    }

    private fun ProviderConfig.toEntity(): ProviderConfigEntity {
        return ProviderConfigEntity(
            providerId = providerId,
            timeoutMs = timeoutMs,
            preferredMirrorUrl = preferredMirrorUrl,
            isEnabled = isEnabled,
            searchEnabled = searchEnabled,
            browseEnabled = browseEnabled,
            downloadEnabled = downloadEnabled,
            useAnonymous = useAnonymous,
            sortPreference = sortPreference,
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/**
 * Domain model for provider configuration.
 *
 * Sweep Finding #1 (2026-05-17): adds [useAnonymous] so per-provider
 * anonymous-mode opt-in survives across process restarts.
 */
data class ProviderConfig(
    val providerId: String,
    val timeoutMs: Int = 10_000,
    val preferredMirrorUrl: String? = null,
    val isEnabled: Boolean = true,
    val searchEnabled: Boolean = true,
    val browseEnabled: Boolean = true,
    val downloadEnabled: Boolean = true,
    val useAnonymous: Boolean = false,
    val sortPreference: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
