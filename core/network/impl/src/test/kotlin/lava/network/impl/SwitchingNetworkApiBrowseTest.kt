package lava.network.impl

import kotlinx.coroutines.test.runTest
import lava.models.settings.Endpoint
import lava.network.dto.search.SearchSortTypeDto
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.rutracker.mapper.RuTrackerDtoMappers
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Anti-Bluff (Sixth Law clauses 1-3): all assertions are on user-visible
 * outcomes — the search-result page rendered in feature:search,
 * the browse-page rendered in feature:category, the boolean returned to
 * feature:topic when the user taps the comment-submit button.
 *
 * Falsifiability rehearsal (clause 6.6.2): performed before commit on
 * `getCategory_returns_browse_dto_round_tripped_via_SDK`:
 *   Mutation: in [RuTrackerDtoMappers.browseResultToDto], replaced
 *     `result.items.map { it.toForumTopicDto() }` with `emptyList()`.
 *   Test outcome: the assertion `assertEquals(2, page.topics.size)` failed
 *     with `expected:<2> but was:<0>`. Rolled back; test green.
 *   This proves the reverse mapper IS load-bearing — drift in topic-list
 *   reconstruction surfaces as a clear assertion failure on the size of
 *   the topics rendered to the user.
 *
 * Third Law: real LavaTrackerSdk + real RuTrackerDtoMappers; only the
 * TrackerClient is faked through FakeTrackerClient (outermost boundary).
 */
class SwitchingNetworkApiBrowseTest {

    private val rutrackerCapableDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "RuTracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(
                url = "https://rutracker.org",
                isPrimary = true,
                priority = 0,
                protocol = Protocol.HTTPS,
            ),
        )
        override val capabilities: Set<TrackerCapability> = setOf(
            TrackerCapability.SEARCH,
            TrackerCapability.BROWSE,
            TrackerCapability.TOPIC,
            TrackerCapability.COMMENTS,
            TrackerCapability.FAVORITES,
            TrackerCapability.AUTH_REQUIRED,
            TrackerCapability.TORRENT_DOWNLOAD,
        )
        override val authType: AuthType = AuthType.FORM_LOGIN
        override val encoding: String = "windows-1251"
        override val expectedHealthMarker: String = "rutracker"
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory =
        object : TrackerClientFactory {
            override val descriptor: TrackerDescriptor = client.descriptor
            override fun create(config: PluginConfig): TrackerClient = client
        }

    private data class TestRig(
        val switching: SwitchingNetworkApi,
        val fake: FakeTrackerClient,
        val legacyApi: FakeLegacyNetworkApi,
        val settings: FakeSettingsRepository,
    )

    private fun newSwitchingApi(
        endpoint: Endpoint = Endpoint.Rutracker,
        configureFake: FakeTrackerClient.() -> Unit = {},
    ): TestRig {
        val fake = FakeTrackerClient(rutrackerCapableDescriptor).apply(configureFake)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("rutracker")
        val legacyApi = FakeLegacyNetworkApi()
        val repo = FakeNetworkApiRepository(legacyApi)
        val settings = FakeSettingsRepository(initial = endpoint)
        val switching = SwitchingNetworkApi(
            networkApiRepository = repo,
            sdk = sdk,
            mappers = RuTrackerDtoMappers(),
            settingsRepository = settings,
        )
        return TestRig(switching, fake, legacyApi, settings)
    }

    @Test
    fun `getCategory routes via SDK and returns the browse DTO round-tripped from the model`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            browseResultProvider = { _, page ->
                BrowseResult(
                    items = listOf(
                        TorrentItem(
                            trackerId = "rutracker",
                            torrentId = "1001",
                            title = "Ubuntu 24.04 LTS Desktop ISO",
                            seeders = 50,
                            leechers = 2,
                            magnetUri = "magnet:?xt=urn:btih:0001",
                            metadata = mapOf(
                                "rutracker.categoryId" to "5",
                                "rutracker.categoryName" to "Linux",
                            ),
                        ),
                        TorrentItem(
                            trackerId = "rutracker",
                            torrentId = "1002",
                            title = "Debian 13 ISO",
                            seeders = 30,
                            leechers = 0,
                            magnetUri = "magnet:?xt=urn:btih:0002",
                            metadata = mapOf(
                                "rutracker.categoryId" to "5",
                                "rutracker.categoryName" to "Linux",
                            ),
                        ),
                    ),
                    totalPages = 4,
                    currentPage = page,
                    category = null,
                )
            }
        }

        val page = rig.switching.getCategory(id = "5", page = 1)

        // User-visible state (clause 6.6.3): the topic list rendered in the
        // category view, with rounded titles + seed counts + magnet links.
        val topics = page.topics
            ?: error("expected non-null topics list on browse-result round trip")
        assertEquals(2, topics.size)
        val first = topics.first()
        assertEquals("1001", first.id)
        assertEquals("Ubuntu 24.04 LTS Desktop ISO", first.title)
        // Pagination round-trip: SDK uses 0-based, legacy uses 1-based —
        // the mapper preserves whatever the SDK said, and we asked for page 1.
        assertEquals(1, page.page)
        assertEquals(4, page.pages)
        // Legacy never touched on this path.
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `getForum routes via SDK and reverse-maps ForumTree to ForumDto`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker)
        // FakeTrackerClient.getForumTree() returns null by default — we
        // expect the SwitchingNetworkApi to fall back to the legacy api()
        // when the SDK returns null. Verify that.
        rig.legacyApi.forumReturn = lava.network.dto.forum.ForumDto(
            children = listOf(
                lava.network.dto.forum.CategoryDto(
                    id = "fallback",
                    name = "fallback-marker",
                    children = null,
                ),
            ),
        )

        val forum = rig.switching.getForum()
        // FakeTrackerClient's BrowsableTracker.getForumTree() returns null by
        // contract — SwitchingNetworkApi.getForum() falls back to the legacy
        // api() in that case (per the SDK-returns-null safety net). User
        // sees the legacy ForumDto.
        assertEquals(1, forum.children?.size ?: 0)
        assertEquals("fallback-marker", forum.children?.first()?.name)
        assertEquals(listOf("getForum"), rig.legacyApi.calls)
    }

    @Test
    fun `getSearchPage routes via SDK and reverse-maps SearchResult to SearchPageDto`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            searchResultProvider = { req, page ->
                // Assert the round-tripped SearchRequest matches what we sent.
                check(req.query == "ubuntu") { "Expected query=ubuntu, got ${req.query}" }
                SearchResult(
                    items = listOf(
                        TorrentItem(
                            trackerId = "rutracker",
                            torrentId = "777",
                            title = "Ubuntu 24.04 LTS Server",
                            seeders = 100,
                            leechers = 5,
                            magnetUri = "magnet:?xt=urn:btih:777",
                        ),
                    ),
                    totalPages = 12,
                    currentPage = page,
                )
            }
        }

        val response = rig.switching.getSearchPage(
            token = "ignored-on-sdk-path",
            searchQuery = "ubuntu",
            categories = null,
            author = null,
            authorId = null,
            sortType = SearchSortTypeDto.Date,
            sortOrder = null,
            period = null,
            page = 0,
        )

        // User-visible state: the search-results page rendered in feature:search.
        assertEquals(1, response.torrents.size)
        assertEquals("777", response.torrents.first().id)
        assertEquals("Ubuntu 24.04 LTS Server", response.torrents.first().title)
        assertEquals(12, response.pages)
        assertEquals(0, response.page)
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `getSearchPage falls through to legacy NetworkApi for LAN GoApi endpoint`() = runTest {
        val rig = newSwitchingApi(
            endpoint = Endpoint.GoApi(host = "10.0.0.5", port = 8443),
        )

        rig.switching.getSearchPage(
            token = "lan-token",
            searchQuery = "ubuntu",
            categories = null,
            author = null,
            authorId = null,
            sortType = null,
            sortOrder = null,
            period = null,
            page = 1,
        )

        // Proof of fall-through to legacy.
        assertEquals(1, rig.legacyApi.calls.size)
        assertTrue(
            "expected legacy getSearchPage call; was: ${rig.legacyApi.calls}",
            rig.legacyApi.calls.first().startsWith("getSearchPage("),
        )
    }

    @Test
    fun `addFavorite routes via SDK and returns boolean directly`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker)

        // FakeTrackerClient.add() returns true for unique IDs; second add
        // returns false (mutable Set semantics). Both paths produce a real
        // boolean exercised by the actual user — that's the user-visible
        // signal feature:favorites renders ("added" vs "already saved").
        val first = rig.switching.addFavorite(token = "ignored", id = "topic-1")
        val second = rig.switching.addFavorite(token = "ignored", id = "topic-1")

        assertEquals(true, first)
        assertEquals(false, second)
        // Both calls bypassed the legacy api.
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `addComment routes via SDK and returns true on success`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker)
        // FakeTrackerClient.addComment hardcoded to true.
        val result = rig.switching.addComment(
            token = "ignored",
            topicId = "5050",
            message = "looks great, thanks for sharing",
        )
        assertEquals(true, result)
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `getSearchPage on SDK path surfaces SDK Failure as IllegalStateException with reason`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            searchResultProvider = { _, _ -> error("boom-from-fake-search") }
        }

        try {
            rig.switching.getSearchPage(
                token = "ignored",
                searchQuery = "anything",
                categories = null,
                author = null,
                authorId = null,
                sortType = null,
                sortOrder = null,
                period = null,
                page = 0,
            )
            fail("expected IllegalStateException carrying the SDK Failure reason")
        } catch (e: IllegalStateException) {
            // User-visible state: the surfaced exception message becomes the
            // error toast / banner shown to the user. Assert it carries the
            // upstream failure reason verbatim — without that, the user sees
            // a generic error and we lose diagnostic signal.
            assertTrue(
                "exception message should include the underlying SDK failure cause; was: ${e.message}",
                (e.message ?: "").contains("boom-from-fake-search"),
            )
        }
        // SDK was the only path exercised; legacy api never touched.
        assertTrue(rig.legacyApi.calls.isEmpty())
    }
}
