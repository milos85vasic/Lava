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
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SP-4 Phase A+B (Task 15) — `listAvailableTrackers()` MUST union user-cloned
 * providers (from `cloned_provider` Room table) with the base set surfaced by
 * the tracker registry.
 *
 * Anti-Bluff (clauses 1-3, 6.J):
 *  - Primary assertions are on the **list the user picker UI reads** — the
 *    cloned descriptor's `trackerId`, `displayName`, and `baseUrls` are the
 *    exact fields the picker renders. Asserting on these is asserting on
 *    user-visible state.
 *  - The fake DAO is behaviourally equivalent to the Room one for the
 *    operations exercised: `getAll()` returns the in-memory list verbatim.
 *
 * Falsifiability rehearsal (clause 6.6.2 / Seventh-Law clause 1):
 *  - Mutation: replaced `return base + synthetic` with `return base` in
 *    [LavaTrackerSdk.listAvailableTrackers].
 *  - Re-ran [union returns base plus a synthetic descriptor for the cloned row].
 *  - Observed-Failure (verbatim from test runner):
 *      "expected:<2> but was:<1>" on the size assertion, followed by
 *      "cloned descriptor must appear in listAvailableTrackers" message.
 *  - Reverted: yes.
 */
class LavaTrackerSdkClonedProvidersTest {

    /** Behaviourally-equivalent fake (Sixth-Law, Third Law on bluff fakes). */
    private class FakeClonedProviderDao : ClonedProviderDao {
        val rows = mutableListOf<ClonedProviderEntity>()
        private val flow = MutableStateFlow<List<ClonedProviderEntity>>(emptyList())

        override fun observeAll() = flow
        override suspend fun getAll(): List<ClonedProviderEntity> = rows.toList()

        override suspend fun upsert(entity: ClonedProviderEntity) {
            rows.removeAll { it.syntheticId == entity.syntheticId }
            rows.add(entity)
            flow.value = rows.toList()
        }

        override suspend fun softDelete(id: String, deletedAt: Long) {
            rows.indices.toList().forEach { i ->
                if (rows[i].syntheticId == id) rows[i] = rows[i].copy(deletedAt = deletedAt)
            }
            flow.value = rows.filter { it.deletedAt == null }.toList()
        }

        override suspend fun delete(id: String) {
            rows.removeAll { it.syntheticId == id }
            flow.value = rows.toList()
        }
    }

    private val rutrackerDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "RuTracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://rutracker.org", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(
            TrackerCapability.SEARCH,
            TrackerCapability.BROWSE,
            TrackerCapability.TOPIC,
            TrackerCapability.AUTH_REQUIRED,
        )
        override val authType: AuthType = AuthType.FORM_LOGIN
        override val encoding: String = "Windows-1251"
        override val expectedHealthMarker: String = "rutracker"
        override val verified: Boolean = true
        override val supportsAnonymous: Boolean = false
        override val apiSupported: Boolean = true
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory =
        object : TrackerClientFactory {
            override val descriptor: TrackerDescriptor = client.descriptor
            override fun create(config: PluginConfig): TrackerClient = client
        }

    @Test
    fun `listAvailableTrackers returns base set when DAO is empty`() {
        val registry = DefaultTrackerRegistry().apply {
            register(factoryFor(FakeTrackerClient(rutrackerDescriptor)))
        }
        val dao = FakeClonedProviderDao() // no rows
        val sdk = LavaTrackerSdk(registry, clonedProviderDao = dao)

        val ids = sdk.listAvailableTrackers().map { it.trackerId }
        // Primary assertion — what the picker UI shows.
        assertEquals(listOf("rutracker"), ids)
    }

    @Test
    fun `listAvailableTrackers returns base set when DAO is not wired`() {
        val registry = DefaultTrackerRegistry().apply {
            register(factoryFor(FakeTrackerClient(rutrackerDescriptor)))
        }
        // Legacy ctor path — no DAO supplied.
        val sdk = LavaTrackerSdk(registry)

        val ids = sdk.listAvailableTrackers().map { it.trackerId }
        assertEquals(listOf("rutracker"), ids)
    }

    @Test
    fun `union returns base plus a synthetic descriptor for the cloned row`() = runBlocking {
        val registry = DefaultTrackerRegistry().apply {
            register(factoryFor(FakeTrackerClient(rutrackerDescriptor)))
        }
        val dao = FakeClonedProviderDao()
        dao.upsert(
            ClonedProviderEntity(
                syntheticId = "rutracker.clone.eu-mirror",
                sourceTrackerId = "rutracker",
                displayName = "RuTracker EU Mirror",
                primaryUrl = "https://rutracker.eu",
            ),
        )
        val sdk = LavaTrackerSdk(registry, clonedProviderDao = dao)

        val all = sdk.listAvailableTrackers()

        // Primary assertion 1 — size grew by exactly one (user-visible: picker now shows 2 entries).
        assertEquals(
            "cloned descriptor must appear in listAvailableTrackers",
            2,
            all.size,
        )
        // Primary assertion 2 — base "rutracker" still present.
        assertNotNull(
            "base rutracker descriptor must remain",
            all.firstOrNull { it.trackerId == "rutracker" },
        )
        // Primary assertion 3 — synthetic descriptor carries the cloned id, display name, primary URL.
        val synth = all.firstOrNull { it.trackerId == "rutracker.clone.eu-mirror" }
        assertNotNull("synthetic cloned descriptor must appear with syntheticId", synth)
        synth!!
        assertEquals("RuTracker EU Mirror", synth.displayName)
        assertEquals(
            listOf(MirrorUrl(url = "https://rutracker.eu", isPrimary = true)),
            synth.baseUrls,
        )
        // Primary assertion 4 — capabilities + authType + encoding delegate to source.
        assertEquals(rutrackerDescriptor.capabilities, synth.capabilities)
        assertEquals(AuthType.FORM_LOGIN, synth.authType)
        assertEquals("Windows-1251", synth.encoding)
        assertTrue("clone inherits source verified flag", synth.verified)
        assertTrue("clone inherits source apiSupported flag", synth.apiSupported)
    }

    @Test
    fun `cloned row whose source is unregistered is skipped`() = runBlocking {
        // Source "rutracker" NOT registered — the clone has no carrier behaviour.
        val registry = DefaultTrackerRegistry()
        val dao = FakeClonedProviderDao()
        dao.upsert(
            ClonedProviderEntity(
                syntheticId = "rutracker.clone.orphan",
                sourceTrackerId = "rutracker",
                displayName = "Orphan Clone",
                primaryUrl = "https://orphan.test",
            ),
        )
        val sdk = LavaTrackerSdk(registry, clonedProviderDao = dao)

        val ids = sdk.listAvailableTrackers().map { it.trackerId }
        assertEquals(
            "clone without source must not surface",
            emptyList<String>(),
            ids,
        )
    }
}
