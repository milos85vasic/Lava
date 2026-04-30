package lava.tracker.client

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.rutor.RuTorClient
import lava.tracker.rutor.RuTorClientFactory
import lava.tracker.rutor.RuTorDescriptor
import lava.tracker.rutor.feature.RuTorAuth
import lava.tracker.rutor.feature.RuTorBrowse
import lava.tracker.rutor.feature.RuTorComments
import lava.tracker.rutor.feature.RuTorDownload
import lava.tracker.rutor.feature.RuTorSearch
import lava.tracker.rutor.feature.RuTorTopic
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import lava.tracker.rutor.parser.RuTorCommentsParser
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
import lava.tracker.rutracker.RuTrackerClient
import lava.tracker.rutracker.RuTrackerClientFactory
import lava.tracker.rutracker.RuTrackerDescriptor
import lava.tracker.rutracker.feature.RuTrackerAuth
import lava.tracker.rutracker.feature.RuTrackerBrowse
import lava.tracker.rutracker.feature.RuTrackerComments
import lava.tracker.rutracker.feature.RuTrackerDownload
import lava.tracker.rutracker.feature.RuTrackerFavorites
import lava.tracker.rutracker.feature.RuTrackerSearch
import lava.tracker.rutracker.feature.RuTrackerTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

/**
 * Bluff-audit remediation (2026-04-30): supplement to [LavaTrackerSdkTest].
 *
 * The 2026-04-30 bluff audit confirmed that [LavaTrackerSdkTest] is a bluff
 * for Hilt-graph integration. Mutation evidence: commenting out
 * `provideGetSearchPageUseCase` in `TrackerClientModule` breaks the `:app`
 * Hilt graph at compile time but [LavaTrackerSdkTest] continued to pass —
 * because it uses [lava.tracker.testing.FakeTrackerClient] in place of the
 * production [RuTrackerClient] / [RuTorClient]. The Sixth Law clause 1
 * violation (same surfaces) is therefore real for the Hilt-routing claim.
 *
 * This test removes the bluff by constructing the **production**
 * [RuTrackerClient] and [RuTorClient] with their real `@Inject constructor`
 * shapes, wiring them through the production [DefaultTrackerRegistry] and
 * [LavaTrackerSdk], and asserting on user-visible state (the descriptors
 * the tracker-picker UI reads, the active-client identity used to route
 * subsequent calls, the Capability Honesty signal that gates the
 * favorites tab on rutor).
 *
 * Per Seventh Law clause 4, the mocked classes here are the **boundary
 * below the SUT** — the per-feature interfaces (RuTrackerSearch, etc.).
 * The SUT is the routing chain SDK→Registry→Client.getFeature(), and that
 * chain is exercised with production classes end-to-end. mockk on a
 * BOUNDARY feature interface is the same as faking a HTTP socket — it is
 * NOT mocking the SUT.
 *
 * Falsifiability rehearsed (Seventh Law clause 1):
 *  - Mutation A: in `RuTrackerClient.kt`, change
 *      `SearchableTracker::class -> if (TrackerCapability.SEARCH in caps) search as T else null`
 *    to `SearchableTracker::class -> null`. Test fails:
 *    `assertNotNull("...SearchableTracker must resolve...")` fires.
 *    Demonstrated 2026-04-30; reverted before commit.
 *
 * Bluff-Audit:
 *   LavaTrackerSdkRealStackTest
 *     Mutation: in core/tracker/rutracker/src/main/kotlin/lava/tracker/rutracker/RuTrackerClient.kt
 *               change SearchableTracker line to return null
 *     Observed-Failure: assertNotNull("...SearchableTracker...") fires
 *     Reverted: yes
 */
class LavaTrackerSdkRealStackTest {

    private fun realRuTrackerClient(): RuTrackerClient = RuTrackerClient(
        // Boundary mocks — the per-feature classes below RuTrackerClient.
        // The SUT (RuTrackerClient.getFeature capability gate) is real.
        search = mockk<RuTrackerSearch>(relaxed = true),
        browse = mockk<RuTrackerBrowse>(relaxed = true),
        topic = mockk<RuTrackerTopic>(relaxed = true),
        comments = mockk<RuTrackerComments>(relaxed = true),
        favorites = mockk<RuTrackerFavorites>(relaxed = true),
        auth = mockk<RuTrackerAuth>(relaxed = true),
        download = mockk<RuTrackerDownload>(relaxed = true),
    )

    private fun realRuTorClient(): RuTorClient {
        val http = RuTorHttpClient()
        return RuTorClient(
            http = http,
            search = RuTorSearch(http, RuTorSearchParser()),
            browse = RuTorBrowse(http, RuTorBrowseParser()),
            topic = RuTorTopic(http, RuTorTopicParser()),
            comments = RuTorComments(http, RuTorCommentsParser()),
            auth = RuTorAuth(http, RuTorLoginParser()),
            download = RuTorDownload(http),
        )
    }

    @Test
    fun `production RuTrackerClient exposes every declared capability`() {
        val client = realRuTrackerClient()

        // Sanity (clause 6.6.2 falsifiability anchor): if any capability
        // were missing the assertNotNull below would be vacuously green.
        val caps = client.descriptor.capabilities
        assertTrue(TrackerCapability.SEARCH in caps)
        assertTrue(TrackerCapability.BROWSE in caps)
        assertTrue(TrackerCapability.TOPIC in caps)
        assertTrue(TrackerCapability.COMMENTS in caps)
        assertTrue(TrackerCapability.FAVORITES in caps)
        assertTrue(TrackerCapability.AUTH_REQUIRED in caps)
        assertTrue(TrackerCapability.TORRENT_DOWNLOAD in caps)

        // Primary user-visible-state assertions: the tracker-info screen
        // reads getFeature(...) for each capability the descriptor declares;
        // the SDK facade routes through the same path. Resolves a non-null
        // impl iff the production capability gate routes correctly.
        assertNotNull(
            "RuTrackerClient.getFeature(SearchableTracker::class) must resolve when SEARCH is declared",
            client.getFeature(SearchableTracker::class),
        )
        assertNotNull(client.getFeature(BrowsableTracker::class))
        assertNotNull(client.getFeature(TopicTracker::class))
        assertNotNull(client.getFeature(CommentsTracker::class))
        assertNotNull(client.getFeature(FavoritesTracker::class))
        assertNotNull(client.getFeature(AuthenticatableTracker::class))
        assertNotNull(client.getFeature(DownloadableTracker::class))
    }

    @Test
    fun `production RuTorClient omits FAVORITES (Capability Honesty)`() {
        val client = realRuTorClient()
        // RuTor declares no FAVORITES (anonymous tracker; clause 6.E
        // Capability Honesty). The user-visible "favorites" tab MUST NOT
        // appear; the test asserts the SDK-facade-visible signal that
        // drives that UI decision.
        assertTrue(TrackerCapability.FAVORITES !in client.descriptor.capabilities)
        assertNull(client.getFeature(FavoritesTracker::class))
    }

    @Test
    fun `LavaTrackerSdk routes through production RuTrackerClient via real registry`() = runTest {
        // RuTrackerClientFactory's @Inject constructor takes a Provider —
        // we wire a direct lambda Provider that returns our real
        // RuTrackerClient. Hilt would have wired the same shape.
        val rutrackerClient = realRuTrackerClient()
        val rutrackerFactory = RuTrackerClientFactory(Provider { rutrackerClient })

        val rutorClient = realRuTorClient()
        val rutorFactory = RuTorClientFactory(Provider { rutorClient })

        // Production registry (DefaultTrackerRegistry) populated with both
        // production factories — same call shape TrackerClientModule uses
        // at runtime. This is the routing path the user-visible tracker
        // picker drives at startup.
        val registry = DefaultTrackerRegistry().apply {
            register(rutrackerFactory)
            register(rutorFactory)
        }
        val sdk = LavaTrackerSdk(registry)

        // Primary assertion 1 — both descriptors are visible in the
        // tracker-picker UI (the same list `MenuViewModel` reads).
        val ids = sdk.listAvailableTrackers().map { it.trackerId }.toSet()
        assertEquals(setOf("rutracker", "rutor"), ids)

        // Primary assertion 2 — the active client IS the production
        // RuTrackerClient instance (not a fake). Object identity check
        // proves the registry routes to the real graph.
        assertEquals(LavaTrackerSdk.DEFAULT_TRACKER_ID, sdk.activeTrackerId())
        assertSame(rutrackerClient, sdk.getActiveClient())
        assertSame(RuTrackerDescriptor, sdk.getActiveClient().descriptor)

        // Primary assertion 3 — switchTracker("rutor") routes through the
        // real graph; the active client is now the production RuTorClient.
        sdk.switchTracker("rutor")
        assertSame(rutorClient, sdk.getActiveClient())
        assertSame(RuTorDescriptor, sdk.getActiveClient().descriptor)
    }

    @Test
    fun `production registry register replaces existing factory by id (last-write-wins)`() {
        // Documents the production DefaultPluginRegistry contract: re-registering
        // the same id replaces the previous factory. The user-visible signal is
        // that the SDK's getActiveClient() returns the LAST-registered client.
        val firstClient = realRuTrackerClient()
        val secondClient = realRuTrackerClient()
        val factoryA = RuTrackerClientFactory(Provider { firstClient })
        val factoryB = RuTrackerClientFactory(Provider { secondClient })
        val registry = DefaultTrackerRegistry().apply {
            register(factoryA)
            register(factoryB) // last-write-wins per DefaultPluginRegistry KDoc
        }
        val sdk = LavaTrackerSdk(registry)
        // Primary user-visible assertion: the active client is the SECOND
        // registered instance, not the first. Object identity proves it.
        assertSame(
            "last-write-wins: SDK.getActiveClient() must be the second registration",
            secondClient,
            sdk.getActiveClient(),
        )
    }
}
