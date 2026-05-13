package lava.tracker.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import lava.database.dao.ClonedProviderDao
import lava.database.entity.ClonedProviderEntity
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.SearchRequest
import lava.tracker.registry.CLONE_BASE_URL_CONFIG_KEY
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * SP-4 Phase F.2 Task 3 (2026-05-13) — when `LavaTrackerSdk.clientFor`
 * resolves a clone synthetic id, it MUST stamp the clone's
 * `primaryUrl` into the [PluginConfig] passed to the SOURCE
 * `TrackerClientFactory.create` call under the well-known key
 * [CLONE_BASE_URL_CONFIG_KEY]. The source's factory is the seam each
 * per-tracker plugin migrates against in Task 4 to actually route
 * HTTP traffic through the clone's URL.
 *
 * Anti-Bluff (§6.J primary):
 *  - Primary assertion is on the config the source factory ACTUALLY
 *    saw at create-time — captured by a recording factory at the
 *    user-touched seam (`multiSearch` of a clone's synthetic id).
 *  - Secondary assertion: the factory was invoked at all (rules out
 *    the "stamp was never reached" failure mode).
 *
 * Falsifiability rehearsal (§6.J clause 2 / §6.N Bluff-Audit):
 *
 *   1. In [LavaTrackerSdk.clientFor], replace the line
 *      `val cloneConfig = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to cloned.primaryUrl))`
 *      with `val cloneConfig = MapPluginConfig()`, then
 *      `val sourceClient = registry.get(cloned.sourceTrackerId, cloneConfig)`
 *      still uses the (now empty) `cloneConfig`.
 *   2. Run this test.
 *   3. Expected failure (verbatim from JUnit): `expected:<https://rutracker.eu>
 *      but was:<null>` on the `cloneBaseUrlOverride` assertion. The
 *      bluff signal is "source factory saw no URL override; per-clone
 *      HTTP routing is broken".
 *   4. Revert; re-run; green.
 */
class LavaTrackerSdkCloneUrlInjectionTest {

    /** Behaviourally-equivalent fake ClonedProviderDao (Third Law). */
    private class FakeClonedDao : ClonedProviderDao {
        val rows = mutableListOf<ClonedProviderEntity>()
        private val flow = MutableStateFlow<List<ClonedProviderEntity>>(emptyList())
        override fun observeAll() = flow
        override suspend fun getAll(): List<ClonedProviderEntity> =
            rows.filter { it.deletedAt == null }
        override suspend fun upsert(entity: ClonedProviderEntity) {
            rows.removeAll { it.syntheticId == entity.syntheticId }
            rows.add(entity)
            flow.value = rows.filter { it.deletedAt == null }.toList()
        }
        override suspend fun softDelete(id: String, deletedAt: Long) {
            rows.indices.toList().forEach { i ->
                if (rows[i].syntheticId == id) rows[i] = rows[i].copy(deletedAt = deletedAt)
            }
            flow.value = rows.filter { it.deletedAt == null }.toList()
        }
        override suspend fun delete(id: String) {
            rows.removeAll { it.syntheticId == id }
            flow.value = rows.filter { it.deletedAt == null }.toList()
        }
    }

    /**
     * Records the [PluginConfig] passed to [create] so the test can
     * assert on the stamped override. Returns a fake client that
     * declares SEARCH so [LavaTrackerSdk.multiSearch] reaches the
     * factory (any feature impl would do; SEARCH is the smallest).
     */
    private class RecordingFactory(
        override val descriptor: TrackerDescriptor,
    ) : TrackerClientFactory {
        var lastConfig: PluginConfig? = null
        var createCallCount: Int = 0
        override fun create(config: PluginConfig): TrackerClient {
            lastConfig = config
            createCallCount += 1
            return FakeTrackerClient(descriptor)
        }
    }

    private val rutrackerDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "RuTracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://rutracker.org", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.FORM_LOGIN
        override val encoding: String = "Windows-1251"
        override val expectedHealthMarker: String = "rutracker"
        override val verified: Boolean = true
        override val supportsAnonymous: Boolean = false
        override val apiSupported: Boolean = true
    }

    @Test
    fun `multiSearch of a clone synthetic id stamps primaryUrl into the source factory's PluginConfig`() = runBlocking {
        val recording = RecordingFactory(rutrackerDescriptor)
        val registry = DefaultTrackerRegistry().apply { register(recording) }
        val dao = FakeClonedDao()
        val clonePrimaryUrl = "https://rutracker.eu"
        dao.upsert(
            ClonedProviderEntity(
                syntheticId = "rutracker.clone.eu",
                sourceTrackerId = "rutracker",
                displayName = "RuTracker EU",
                primaryUrl = clonePrimaryUrl,
            ),
        )
        val sdk = LavaTrackerSdk(registry, clonedProviderDao = dao)

        sdk.multiSearch(
            request = SearchRequest(query = "any"),
            providerIds = listOf("rutracker.clone.eu"),
        )

        // §6.J secondary — the source factory was reached.
        assertEquals(
            "source factory must be invoked exactly once for the clone's source tracker",
            1,
            recording.createCallCount,
        )
        val seen = recording.lastConfig
        assertNotNull("source factory must have seen a PluginConfig", seen)

        // §6.J primary — the stamped override is observable at the seam.
        assertEquals(
            "source factory's PluginConfig must carry the clone's primaryUrl under CLONE_BASE_URL_CONFIG_KEY",
            clonePrimaryUrl,
            seen!!.cloneBaseUrlOverride,
        )
        // Also assert via the raw key — guards against the extension's
        // own implementation drifting from the constant.
        assertEquals(
            "raw[CLONE_BASE_URL_CONFIG_KEY] must equal primaryUrl",
            clonePrimaryUrl,
            seen.raw[CLONE_BASE_URL_CONFIG_KEY],
        )
    }

    @Test
    fun `multiSearch of an ORIGINAL tracker id stamps NO override into the source factory's PluginConfig`() = runBlocking {
        val recording = RecordingFactory(rutrackerDescriptor)
        val registry = DefaultTrackerRegistry().apply { register(recording) }
        val dao = FakeClonedDao()
        // No clone rows seeded — straight original-tracker path.
        val sdk = LavaTrackerSdk(registry, clonedProviderDao = dao)

        sdk.multiSearch(
            request = SearchRequest(query = "any"),
            providerIds = listOf("rutracker"),
        )

        // Original-tracker path must NOT leak a clone override into the
        // factory's config — otherwise the source's HTTP client would
        // route to the clone URL when no clone is involved.
        val seen = recording.lastConfig
        assertNotNull("source factory must have seen a PluginConfig", seen)
        assertEquals(
            "original-tracker path must NOT stamp cloneBaseUrlOverride",
            null,
            seen!!.cloneBaseUrlOverride,
        )
    }
}
