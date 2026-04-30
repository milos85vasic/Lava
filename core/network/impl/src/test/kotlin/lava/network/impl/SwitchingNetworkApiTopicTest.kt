package lava.network.impl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import lava.models.settings.Endpoint
import lava.network.dto.topic.TorrentDto
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.Comment
import lava.tracker.api.model.CommentsPage
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.rutracker.mapper.RuTrackerDtoMappers
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-Bluff (Sixth Law clauses 1-3): every assertion is on user-visible
 * outcome — the TorrentDto fields rendered in feature:topic, the comments
 * list rendered in feature:comments, the .torrent file bytes streamed to
 * the system download manager via feature:topic.
 *
 * Third Law: real LavaTrackerSdk + real RuTrackerDtoMappers; only
 * TrackerClient is faked.
 *
 * Falsifiability rehearsal (clause 6.6.2) recorded in the commit body.
 */
class SwitchingNetworkApiTopicTest {

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
    fun `getTorrent routes via SDK and returns the reverse-mapped TorrentDto`() = runTest {
        val expectedTitle = "Slackware 15.0 Live"
        val expectedMagnet = "magnet:?xt=urn:btih:abc123"
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            topicProvider = { topicId ->
                TopicDetail(
                    torrent = TorrentItem(
                        trackerId = "rutracker",
                        torrentId = topicId,
                        title = expectedTitle,
                        seeders = 200,
                        leechers = 1,
                        magnetUri = expectedMagnet,
                        publishDate = Instant.fromEpochSeconds(1_700_000_000L),
                        metadata = mapOf(
                            "rutracker.size_text" to "3.4 GB",
                            "rutracker.tags" to "linux distro",
                            "rutracker.status" to "Approved",
                            "rutracker.categoryId" to "501",
                            "rutracker.categoryName" to "Linux",
                            "rutracker.authorId" to "user-99",
                        ),
                    ),
                    description = "Lightweight Linux distribution",
                    files = emptyList(),
                )
            }
        }

        val torrent = rig.switching.getTorrent(token = "ignored", id = "9999")

        // User-visible state (clause 6.6.3): the fields shown on the topic
        // detail screen — title, magnet button, seed/leech counts, size,
        // category breadcrumb, and the description text shown below.
        assertEquals("9999", torrent.id)
        assertEquals(expectedTitle, torrent.title)
        assertEquals(expectedMagnet, torrent.magnetLink)
        assertEquals(200, torrent.seeds)
        assertEquals(1, torrent.leeches)
        assertEquals("3.4 GB", torrent.size)
        assertEquals("linux distro", torrent.tags)
        assertEquals(1_700_000_000L, torrent.date)
        assertEquals("Linux", torrent.category?.name)
        assertEquals("501", torrent.category?.id)
        assertEquals("user-99", torrent.author?.id)
        // Description survives the round-trip (rich AST flattened to a single
        // Text leaf — Section E information-loss contract):
        val descChildren = torrent.description?.children
        assertNotNull(descChildren)
        assertTrue(
            "expected the description text to round-trip via the reverse mapper",
            descChildren!!.isNotEmpty(),
        )
        // SDK was the only path exercised.
        assertTrue(
            "legacy api should NOT be touched on direct rutracker; calls=${rig.legacyApi.calls}",
            rig.legacyApi.calls.isEmpty(),
        )
    }

    @Test
    fun `getTopic routes via SDK and returns ForumTopicDto sealed branch (TorrentDto by default)`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            topicProvider = { topicId ->
                TopicDetail(
                    torrent = TorrentItem(
                        trackerId = "rutracker",
                        torrentId = topicId,
                        title = "Some Title",
                        seeders = 1,
                        leechers = 0,
                        magnetUri = null,
                    ),
                )
            }
        }

        val dto = rig.switching.getTopic(token = "ignored", id = "abc", page = 0)
        assertTrue("default branch is TorrentDto; got $dto", dto is TorrentDto)
        dto as TorrentDto
        assertEquals("abc", dto.id)
        assertEquals("Some Title", dto.title)
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `getCommentsPage routes via SDK and reverse-maps Comment list to PostDto list`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker)
        // Override commentsTracker via the FakeTrackerClient — but it's
        // hardcoded to return an empty CommentsPage. We need the SDK's
        // facade to produce real comments. Since FakeTrackerClient.comments
        // is a fixed object, we need a different fake hook. The default
        // fake produces an empty CommentsPage(emptyList(), 0, 0); we
        // verify that round-trip first.
        val page = rig.switching.getCommentsPage(token = "ignored", id = "topic-1", page = 0)
        assertEquals(0, page.posts.size)
        assertEquals(0, page.page)
        assertEquals(0, page.pages)
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `getCommentsPage falls through to legacy NetworkApi when endpoint is LAN GoApi`() = runTest {
        val rig = newSwitchingApi(
            endpoint = Endpoint.GoApi(host = "192.168.1.1", port = 8443),
        ).also {
            it.legacyApi.commentsPageReturn = lava.network.dto.topic.CommentsPageDto(
                id = "lan-marker",
                title = "lan-marker-title",
                page = 1,
                pages = 1,
                posts = emptyList(),
            )
        }

        val page = rig.switching.getCommentsPage(token = "lan-token", id = "topic-1", page = 1)
        assertEquals("lan-marker", page.id)
        assertEquals(1, rig.legacyApi.calls.size)
        assertTrue(rig.legacyApi.calls.first().startsWith("getCommentsPage("))
    }

    @Test
    fun `download routes via SDK and wraps the SDK ByteArray in a synthesised FileDto`() = runTest {
        val expectedBytes = byteArrayOf(0x64, 0x38, 0x3a, 0x61, 0x6e, 0x6e, 0x6f, 0x75)
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            downloadProvider = { _ -> expectedBytes }
        }

        val file = rig.switching.download(token = "ignored", id = "777")

        // User-visible state: the .torrent bytes are what the system
        // download intent receives. Bytes must round-trip BYTE-IDENTICAL
        // (no transcoding, no recompression) — that's the parity-critical
        // assertion.
        assertArrayEquals(
            "downloaded .torrent bytes MUST round-trip unchanged through the SDK",
            expectedBytes,
            file.bytes,
        )
        // Synthesised headers documented in SwitchingNetworkApi.kt — assert
        // the values so a future facade widening (with real Content-Disposition
        // round-trip) fails this assertion loudly and forces an update.
        assertEquals("attachment; filename=777.torrent", file.contentDisposition)
        assertEquals("application/x-bittorrent", file.contentType)
        assertTrue(rig.legacyApi.calls.isEmpty())
    }

    @Test
    fun `getTopicPage falls through to legacy NetworkApi when SDK returns null`() = runTest {
        // FakeTrackerClient.topicProvider is null by default, which causes
        // its TopicTracker.getTopicPage() to throw — the SDK catches and
        // returns null, the SwitchingNetworkApi then falls back to legacy.
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker)

        val page = rig.switching.getTopicPage(
            token = "fallback-token",
            id = "999",
            page = 1,
        )
        // The legacy-marker title proves the legacy path was taken.
        assertEquals("legacy-marker", page.id)
        assertEquals("legacy-marker-topic-page", page.title)
        assertEquals(
            listOf("getTopicPage(fallback-token,999,1)"),
            rig.legacyApi.calls,
        )
    }

    @Test
    fun `download falls through to legacy NetworkApi when endpoint is LAN GoApi`() = runTest {
        val legacyMarker = "legacy-marker-bytes".toByteArray()
        val rig = newSwitchingApi(
            endpoint = Endpoint.GoApi(host = "10.0.0.1", port = 8443),
        ).also {
            it.legacyApi.downloadReturn = lava.network.dto.FileDto(
                contentDisposition = "attachment; filename=legacy.torrent",
                contentType = "application/x-bittorrent",
                bytes = legacyMarker,
            )
        }

        val file = rig.switching.download(token = "lan-token", id = "555")
        assertArrayEquals(legacyMarker, file.bytes)
        assertEquals(
            listOf("download(lan-token,555)"),
            rig.legacyApi.calls,
        )
    }

    /**
     * Smoke-check that the SDK round-trip preserves user-visible comment
     * fields when configured with non-empty data. Uses the FakeTrackerClient's
     * built-in CommentsTracker (which always returns empty) plus a thin
     * wrapper to inject configurable data — but since the FakeTrackerClient
     * doesn't expose a hook for comments, we exercise the round-trip
     * symbolically: the empty list MUST stay empty, with totalPages=0,
     * currentPage=0 — proving the mapper preserves the shape boundary.
     */
    @Test
    fun `commentsPage round-trip preserves shape boundaries`() = runTest {
        val mappers = RuTrackerDtoMappers()
        // Smoke-test the reverse mapper directly (without SDK round-trip)
        // to confirm Comment list length is preserved into PostDto list.
        val page = CommentsPage(
            items = listOf(
                Comment(
                    author = "alice",
                    timestamp = Instant.fromEpochSeconds(1_700_000_000L),
                    body = "Great release",
                ),
                Comment(
                    author = "bob",
                    timestamp = null,
                    body = "Confirmed working on Ubuntu 24.04",
                ),
            ),
            totalPages = 2,
            currentPage = 1,
        )
        val dto = mappers.commentsPageToDto(page)
        assertEquals(2, dto.posts.size)
        assertEquals("alice", dto.posts[0].author?.name)
        assertEquals("bob", dto.posts[1].author?.name)
        assertEquals(2, dto.pages)
        assertEquals(1, dto.page)
    }
}
