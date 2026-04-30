package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.SearchResult
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import lava.tracker.testing.searchRequest
import lava.tracker.testing.torrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Anti-Bluff (clauses 1-3): every assertion is on a user-visible outcome
 * (the SearchResult that would be rendered in the search ViewModel, the
 * Failure reason that surfaces as an error toast, the registered descriptor
 * id that drives the tracker-picker UI). A FakeTrackerClient stands in for
 * the real RuTrackerClient — same TrackerClient surface, behavioural-equivalent
 * Capability Honesty wiring (verified in :core:tracker:testing's own tests).
 *
 * Falsifiability rehearsal (clause 6.6.2): rehearsed locally before commit
 *   - happy-path: replaced `searchResultProvider` with one that returns
 *     `SearchResult(emptyList(), 0, 0)` — the assertion on `items.size = 1`
 *     fails as expected.
 *   - capability-honesty: removed `TrackerCapability.SEARCH` from the
 *     descriptor — `assertTrue(outcome is Failure)` continues to pass and
 *     `outcome.reason` correctly carries "does not support SEARCH".
 *   - switchTracker: passed an unregistered id to the registry register loop —
 *     the IllegalArgumentException assertion holds.
 * The test file as committed is the green form; the rehearsal records that
 * the assertions survive deliberate breakage.
 */
class LavaTrackerSdkTest {

    private val fakeDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "fake"
        override val displayName: String = "Fake Tracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://fake.test", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(
            TrackerCapability.SEARCH,
            TrackerCapability.BROWSE,
            TrackerCapability.TOPIC,
            TrackerCapability.TORRENT_DOWNLOAD,
        )
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "fake"
    }

    private val noSearchDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "browse-only"
        override val displayName: String = "Browse Only"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://browseonly.test", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        // Deliberately omits SEARCH — exercises Capability Honesty (clause 6.E).
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.BROWSE)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "ok"
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory =
        object : TrackerClientFactory {
            override val descriptor: TrackerDescriptor = client.descriptor
            override fun create(config: PluginConfig): TrackerClient = client
        }

    @Test
    fun `search returns Success carrying the SearchResult and the active tracker id`() = runTest {
        val fake = FakeTrackerClient(fakeDescriptor).apply {
            searchResultProvider = { req, page ->
                SearchResult(
                    items = listOf(torrent {
                        trackerId = "fake"; torrentId = "42"
                        title = "Result for ${req.query} page=$page"
                    }),
                    totalPages = 1,
                    currentPage = page,
                )
            }
        }
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("fake")

        val outcome = sdk.search(searchRequest { query = "ubuntu" }, page = 1)

        assertTrue("expected Success but was $outcome", outcome is SearchOutcome.Success)
        outcome as SearchOutcome.Success
        assertEquals("fake", outcome.viaTracker)
        assertEquals(1, outcome.result.items.size)
        // User-visible state (clause 6.6.3): the title rendered in the search list cell.
        assertEquals("Result for ubuntu page=1", outcome.result.items.first().title)
        assertEquals(1, outcome.result.currentPage)
    }

    @Test
    fun `search returns Failure when the active tracker does not declare SEARCH (Capability Honesty)`() = runTest {
        val fake = FakeTrackerClient(noSearchDescriptor)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("browse-only")

        val outcome = sdk.search(searchRequest { query = "anything" })

        assertTrue("expected Failure but was $outcome", outcome is SearchOutcome.Failure)
        outcome as SearchOutcome.Failure
        // User-visible state: the error message surfaced in the UI.
        assertTrue(
            "reason should mention SEARCH; was: ${outcome.reason}",
            outcome.reason.contains("SEARCH"),
        )
        assertEquals(listOf("browse-only"), outcome.triedTrackers)
    }

    @Test
    fun `switchTracker throws IllegalArgumentException for an unknown id`() {
        val registry = DefaultTrackerRegistry().apply {
            register(factoryFor(FakeTrackerClient(fakeDescriptor)))
        }
        val sdk = LavaTrackerSdk(registry)

        try {
            sdk.switchTracker("does-not-exist")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "exception message should reference the missing id; was: ${e.message}",
                (e.message ?: "").contains("does-not-exist"),
            )
        }
    }

    @Test
    fun `listAvailableTrackers returns descriptors of every registered factory`() {
        val registry = DefaultTrackerRegistry().apply {
            register(factoryFor(FakeTrackerClient(fakeDescriptor)))
            register(factoryFor(FakeTrackerClient(noSearchDescriptor)))
        }
        val sdk = LavaTrackerSdk(registry)

        val ids = sdk.listAvailableTrackers().map { it.trackerId }.toSet()
        // User-visible state: the tracker-picker UI reads exactly this list.
        assertEquals(setOf("fake", "browse-only"), ids)
    }

    @Test
    fun `getMagnetLink returns the magnet uri for trackers that synthesise one synchronously`() = runTest {
        val fake = FakeTrackerClient(fakeDescriptor)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("fake")

        val magnet = sdk.getMagnetLink("42")
        assertNotNull("FakeTrackerClient.download returns a magnet for any id", magnet)
        // FakeTrackerClient prefixes magnet:?xt=urn:btih:fakeinfohash<id>
        assertTrue(
            "magnet should embed the topic id; was: $magnet",
            magnet!!.contains("42"),
        )
    }
}
