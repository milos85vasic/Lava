package lava.tracker.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import lava.database.dao.ClonedProviderDao
import lava.database.entity.ClonedProviderEntity
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.KClass

/**
 * SP-4 Phase F.1 clone-search SDK tests.
 *
 * Anti-Bluff (§6.J / §6.L): every assertion is on a user-observable
 * outcome — the search-result rows the user actually sees, with the
 * provider badge ("RuTracker EU" vs "RuTracker.org") reflecting the
 * clone-id re-tag. The pre-Phase-F.1 SDK crashed with
 * IllegalArgumentException whenever a clone id was passed to
 * multiSearch / streamMultiSearch / search / login / etc. — that's
 * the bug Phase F.1 closes.
 *
 * Falsifiability rehearsals recorded in commit body.
 */
class LavaTrackerSdkCloneSearchTest {

    private val sourceDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "RuTracker.org"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://rutracker.org", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "rutracker"
    }

    /** Test fake whose `search` returns deterministic items tagged with the SOURCE id. */
    private class FakeSourceClient(
        override val descriptor: TrackerDescriptor,
        private val items: List<TorrentItem>,
    ) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> {
                val sut = object : SearchableTracker {
                    override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                        SearchResult(items = items, totalPages = 1, currentPage = page)
                }
                sut as T
            }
            else -> null
        }
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory = object : TrackerClientFactory {
        override val descriptor: TrackerDescriptor = client.descriptor
        override fun create(config: PluginConfig): TrackerClient = client
    }

    /** In-memory fake of [ClonedProviderDao]. */
    private class InMemoryClonedProviderDao : ClonedProviderDao {
        private val rows = MutableStateFlow<List<ClonedProviderEntity>>(emptyList())
        override fun observeAll(): Flow<List<ClonedProviderEntity>> = rows.asStateFlow().map { it.toList() }
        override suspend fun getAll(): List<ClonedProviderEntity> = rows.value
        override suspend fun upsert(entity: ClonedProviderEntity) {
            rows.value = rows.value.filterNot { it.syntheticId == entity.syntheticId } + entity
        }
        override suspend fun softDelete(id: String, deletedAt: Long) {
            rows.value = rows.value.map { if (it.syntheticId == id) it.copy(deletedAt = deletedAt) else it }
        }
        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.syntheticId == id }
        }
    }

    private fun sdkWithOneClone(sourceItems: List<TorrentItem>): Pair<LavaTrackerSdk, ClonedProviderEntity> {
        val source = FakeSourceClient(sourceDescriptor, sourceItems)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(source)) }
        val dao = InMemoryClonedProviderDao()
        val clone = ClonedProviderEntity(
            syntheticId = "rutracker.clone.eu",
            sourceTrackerId = "rutracker",
            displayName = "RuTracker EU",
            primaryUrl = "https://rutracker.eu",
        )
        kotlinx.coroutines.runBlocking { dao.upsert(clone) }
        val sdk = LavaTrackerSdk(registry, clonedProviderDao = dao)
        return sdk to clone
    }

    // ---------------------------------------------------------------------
    // Test 1 — clone id does NOT crash multiSearch.
    //
    // Falsifiability: pre-Phase-F.1, registry.get(syntheticId) throws
    // IllegalArgumentException("Unknown plugin id: rutracker.clone.eu").
    // After F.1, clientFor(syntheticId) resolves through the cloned-
    // provider DAO + wraps the source client. Mutation to revert this
    // change (replace clientFor with registry.get) re-introduces the
    // crash and this test fails.
    // ---------------------------------------------------------------------
    @Test
    fun `multiSearch on a clone id does not crash and reports SUCCESS`() = runTest {
        val items = listOf(
            TorrentItem(trackerId = "rutracker", torrentId = "1", title = "Ubuntu ISO"),
        )
        val (sdk, clone) = sdkWithOneClone(items)

        val result = sdk.multiSearch(
            request = SearchRequest(query = "ubuntu"),
            providerIds = listOf(clone.syntheticId),
        )

        assertEquals(1, result.providerStatuses.size)
        val status = result.providerStatuses[0]
        assertEquals(clone.syntheticId, status.providerId)
        assertEquals(
            "expected SUCCESS for clone-id search but was ${status.state} (${status.errorMessage})",
            ProviderSearchState.SUCCESS,
            status.state,
        )
        assertEquals("RuTracker EU", status.displayName)
    }

    // ---------------------------------------------------------------------
    // (A proposed Test 2 — "clone results are tagged with the clone's
    // synthetic id" — was REMOVED during the 2026-05-13 falsifiability
    // rehearsal. Reason: the dedup engine derives ProviderOccurrence
    // .providerId from the map key passed by multiSearch, not from
    // TorrentItem.trackerId. The "re-tag" mutation passed even when
    // reverted, proving the test was asserting on dedup behavior, not
    // on the wrapper's re-tag. Per §6.J/§6.L, the dead re-tag was
    // removed from production code and the bluff test deleted rather
    // than papered over. Test 1's `displayName == "RuTracker EU"`
    // assertion already covers the user-observable surface — the
    // clone's display name reaches the UI through clientFor's
    // descriptor override, NOT through item-level re-tagging.)
}
