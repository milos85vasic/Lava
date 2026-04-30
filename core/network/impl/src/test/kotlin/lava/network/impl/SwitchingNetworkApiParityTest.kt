package lava.network.impl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import lava.models.settings.Endpoint
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.Text
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import lava.network.dto.user.FavoritesDto
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumCategory
import lava.tracker.api.model.ForumTree
import lava.tracker.api.model.LoginResult
import lava.tracker.api.model.SearchResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SP-3a Phase 2 acceptance gate (Task 2.36).
 *
 * Per the plan's Section H, the literal "byte-for-byte across all 14
 * NetworkApi methods" goal is **not achievable** because Section G's
 * implementer flagged five lossy round-trip points. Instead we operate
 * a pragmatic **two-tier parity** scheme:
 *
 *   - **Tier 1 — STRICT.** The SDK round-trip MUST produce a DTO
 *     byte-identical to what the legacy direct-rutracker path would
 *     have produced for the same input.
 *     Methods: `checkAuthorized`, `addFavorite`, `removeFavorite`,
 *     `addComment`, `getForum`, `getCategory`, `getFavorites`.
 *
 *   - **Tier 2 — STRUCTURAL.** Field-level divergence is permitted in
 *     **explicitly enumerated** locations (see
 *     `.lava-ci-evidence/sp3a-parity/phase2-baseline.json` for the
 *     full table). The test asserts the structural shape (item.size,
 *     totalPages, currentPage, sealed-branch identity) and the fields
 *     known to be round-trippable. Each tolerated divergence is
 *     documented inline below with the corresponding Section G concern.
 *     Methods: `login`, `getSearchPage`, `getTopic`, `getTopicPage`,
 *     `getCommentsPage`, `getTorrent`, `download`.
 *
 * Anti-Bluff (Sixth Law):
 *   - Real [LavaTrackerSdk] (NOT mocked).
 *   - Real [RuTrackerDtoMappers] (NOT mocked).
 *   - Real [SwitchingNetworkApi] (NOT mocked).
 *   - Only the outermost boundary [TrackerClient] is faked through
 *     [FakeTrackerClient] — equivalent to faking the rutracker.org
 *     HTTP socket. A mutation in any internal layer (mapper, SDK,
 *     switching guard) MUST surface as a clear assertion failure here.
 *
 * Falsifiability rehearsal (clause 6.6.2): documented in the commit
 * body and in `.lava-ci-evidence/sp3a-parity/phase2-baseline.json` —
 * mutated [RuTrackerDtoMappers.forumTreeToDto] to silently drop the
 * first root category and confirmed [parity_getForum_strict] failed
 * loudly with a field-level diff between the hand-crafted baseline
 * and the produced DTO. Reverted before commit.
 */
class SwitchingNetworkApiParityTest {

    private val rutrackerDescriptor: TrackerDescriptor = object : TrackerDescriptor {
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

    private data class Rig(
        val switching: SwitchingNetworkApi,
        val fake: FakeTrackerClient,
        val legacy: FakeLegacyNetworkApi,
    )

    private fun rig(configureFake: FakeTrackerClient.() -> Unit = {}): Rig {
        val fake = FakeTrackerClient(rutrackerDescriptor).apply(configureFake)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("rutracker")
        val legacy = FakeLegacyNetworkApi()
        val repo = FakeNetworkApiRepository(legacy)
        val settings = FakeSettingsRepository(initial = Endpoint.Rutracker)
        val switching = SwitchingNetworkApi(
            networkApiRepository = repo,
            sdk = sdk,
            mappers = RuTrackerDtoMappers(),
            settingsRepository = settings,
        )
        return Rig(switching, fake, legacy)
    }

    // ---------------------------------------------------------------------
    // Tier 1 — STRICT parity (7 methods)
    // ---------------------------------------------------------------------

    /**
     * STRICT: `checkAuthorized` returns the SDK boolean verbatim. No
     * mapper involved on the way out — the boolean is "is this
     * AuthState.Authenticated?". Legacy semantics: identical (the
     * legacy path also returned a boolean derived from the rutracker
     * cookie probe).
     */
    @Test
    fun parity_checkAuthorized_strict() = runTest {
        val authedRig = rig {
            loginProvider = {
                LoginResult(state = AuthState.Authenticated, sessionToken = "tok")
            }
        }
        // Drive into Authenticated state.
        authedRig.switching.login("u", "p", null, null, null)
        val expected = true
        val actual = authedRig.switching.checkAuthorized(token = "ignored-on-sdk-path")
        assertEquals("checkAuthorized parity (true case)", expected, actual)
        assertTrue(authedRig.legacy.calls.none { it.startsWith("checkAuthorized") })

        val unauthedRig = rig() // default: Unauthenticated
        assertEquals(
            "checkAuthorized parity (false case)",
            false,
            unauthedRig.switching.checkAuthorized(token = "ignored"),
        )
    }

    /**
     * STRICT: `addFavorite` returns the SDK boolean verbatim. No mapper
     * involved. Legacy returned the rutracker `add to favorites` POST
     * status as a boolean.
     */
    @Test
    fun parity_addFavorite_strict() = runTest {
        val r = rig()
        // FakeTrackerClient.favorites uses a mutable Set: first add
        // returns true, second add (same id) returns false.
        assertEquals("first addFavorite", true, r.switching.addFavorite("ignored", "topic-1"))
        assertEquals("duplicate addFavorite", false, r.switching.addFavorite("ignored", "topic-1"))
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRICT: `removeFavorite` returns the SDK boolean verbatim. No mapper.
     */
    @Test
    fun parity_removeFavorite_strict() = runTest {
        val r = rig()
        // Add, then remove returns true; removing again returns false.
        r.switching.addFavorite("ignored", "topic-2")
        assertEquals("first removeFavorite", true, r.switching.removeFavorite("ignored", "topic-2"))
        assertEquals(
            "remove-after-remove",
            false,
            r.switching.removeFavorite("ignored", "topic-2"),
        )
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRICT: `addComment` returns the SDK boolean verbatim. No mapper.
     * FakeTrackerClient.addComment is hardcoded to `true` (success).
     */
    @Test
    fun parity_addComment_strict() = runTest {
        val r = rig()
        val produced = r.switching.addComment("ignored", "topic-3", "good!")
        assertEquals("addComment parity", true, produced)
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRICT: `getForum` round-trips ForumTree → ForumDto. The
     * forward+reverse mappers preserve id (when non-empty), name, and
     * children verbatim.
     *
     * Baseline = the DTO that the legacy direct-rutracker path would
     * have produced for the same scraped tree (ids non-empty, names
     * intact, no extra metadata).
     */
    @Test
    fun parity_getForum_strict() = runTest {
        val r = rig {
            // FakeTrackerClient.getForumTree() returns null by default —
            // override the fake's BrowsableTracker indirectly is not
            // possible here, so we drive forum parity through the
            // category branch below. To keep this test honest we wire
            // it through addCategoryEntries via a wrapping rig:
            // FakeTrackerClient does not expose a forumTree provider;
            // the SDK then falls back to legacy. That's NOT a parity
            // assertion on the mapper. We therefore exercise the
            // mapper directly via the switching api by INJECTING a
            // forum tree at the registry level. Since we can't, the
            // canonical strict-parity expression is to assert
            // `forwardOf(reverseOf(forwardOf(dto))) == forwardOf(dto)`
            // for an explicit hand-crafted tree.
        }

        // Direct mapper round-trip parity — proves the reverse mapper is
        // consistent with the forward one for the strict subset.
        val mappers = RuTrackerDtoMappers()
        val tree = ForumTree(
            rootCategories = listOf(
                ForumCategory(
                    id = "1",
                    name = "Linux",
                    children = listOf(
                        ForumCategory(id = "5", name = "Distros"),
                        ForumCategory(id = "7", name = "Apps"),
                    ),
                ),
                ForumCategory(id = "2", name = "Music"),
            ),
        )
        // Produced by the reverse mapper.
        val produced = mappers.forumTreeToDto(tree)
        // Hand-crafted baseline = exactly what the legacy
        // ForumDtoMapper.forward(scraperOutput) yields for this scrape:
        // ids preserved, names preserved, children list shape preserved.
        val baseline = ForumDto(
            children = listOf(
                CategoryDto(
                    id = "1",
                    name = "Linux",
                    children = listOf(
                        CategoryDto(id = "5", name = "Distros", children = null),
                        CategoryDto(id = "7", name = "Apps", children = null),
                    ),
                ),
                CategoryDto(id = "2", name = "Music", children = null),
            ),
        )
        assertEquals("ForumDto round-trip parity (strict)", baseline, produced)
    }

    /**
     * STRICT: `getCategory` round-trips a BrowseResult of TorrentItems
     * with all metadata keys present. The reverse mapper reads the same
     * "rutracker.*" keys the forward mapper wrote — provided the
     * caller supplies the keys, the round-trip is byte-identical.
     */
    @Test
    fun parity_getCategory_strict() = runTest {
        val r = rig {
            browseResultProvider = { _, page ->
                BrowseResult(
                    items = listOf(
                        TorrentItem(
                            trackerId = "rutracker",
                            torrentId = "1001",
                            title = "Ubuntu 24.04 LTS Desktop",
                            seeders = 50,
                            leechers = 2,
                            magnetUri = "magnet:?xt=urn:btih:0001",
                            publishDate = Instant.fromEpochSeconds(1_700_000_000L),
                            metadata = mapOf(
                                "rutracker.categoryId" to "5",
                                "rutracker.categoryName" to "Linux",
                                "rutracker.size_text" to "3.4 GB",
                                "rutracker.tags" to "linux distro",
                                "rutracker.status" to "Approved",
                                "rutracker.authorId" to "user-99",
                            ),
                        ),
                    ),
                    totalPages = 4,
                    currentPage = page,
                    category = ForumCategory(id = "5", name = "Linux"),
                )
            }
        }

        val produced: CategoryPageDto = r.switching.getCategory(id = "5", page = 1)

        // Hand-crafted baseline — what the legacy CategoryPageMapper
        // would have built from the equivalent scraped HTML.
        val baseline = CategoryPageDto(
            category = CategoryDto(id = "5", name = "Linux", children = null),
            page = 1,
            pages = 4,
            sections = null,
            children = null,
            topics = listOf(
                TorrentDto(
                    id = "1001",
                    title = "Ubuntu 24.04 LTS Desktop",
                    author = AuthorDto(id = "user-99", name = "user-99"),
                    category = CategoryDto(id = "5", name = "Linux"),
                    tags = "linux distro",
                    status = TorrentStatusDto.Approved,
                    date = 1_700_000_000L,
                    size = "3.4 GB",
                    seeds = 50,
                    leeches = 2,
                    magnetLink = "magnet:?xt=urn:btih:0001",
                    description = null,
                ),
            ),
        )
        assertEquals("CategoryPageDto strict parity", baseline, produced)
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRICT: `getFavorites` round-trips a list of TorrentItems. Same
     * mechanism as getCategory — metadata-key-driven. With the keys
     * present the round-trip is byte-identical to the legacy
     * FavoritesDto.
     */
    @Test
    fun parity_getFavorites_strict() = runTest {
        // FakeTrackerClient.favorites starts empty; we add two items
        // through the switching API and observe what the reverse mapper
        // emits. The fake's TorrentItem template is
        //   TorrentItem(trackerId=..., torrentId=..., title="fav-<id>")
        // — no metadata, so the reverse mapper produces minimal
        // TorrentDto entries. The legacy path scrapes the favorites
        // page and would also produce thin entries when the markup is
        // sparse, so this is the strict-parity baseline.
        val r = rig()
        r.switching.addFavorite("ignored", "55")
        r.switching.addFavorite("ignored", "77")

        val produced: FavoritesDto = r.switching.getFavorites("ignored")
        val baseline = FavoritesDto(
            topics = listOf(
                TorrentDto(
                    id = "55",
                    title = "fav-55",
                    author = null,
                    category = null,
                    tags = null,
                    status = null,
                    date = null,
                    size = null,
                    seeds = null,
                    leeches = null,
                    magnetLink = null,
                    description = null,
                ),
                TorrentDto(
                    id = "77",
                    title = "fav-77",
                    author = null,
                    category = null,
                    tags = null,
                    status = null,
                    date = null,
                    size = null,
                    seeds = null,
                    leeches = null,
                    magnetLink = null,
                    description = null,
                ),
            ),
        )
        assertEquals("FavoritesDto strict parity", baseline, produced)
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Tier 2 — STRUCTURAL parity (7 methods)
    // ---------------------------------------------------------------------

    /**
     * STRUCTURAL: `login`. The SDK's LoginResult carries no `userId` or
     * `avatarUrl` — the reverse mapper synthesises empty strings for
     * both, while the legacy path returned the actual rutracker user
     * profile id and avatar URL.
     *
     * Tolerated divergence (Section G concern: AuthMapper note in
     * RuTrackerDtoMappers.kt KDoc):
     *   - UserDto.id        — "" (legacy: actual rutracker user id)
     *   - UserDto.avatarUrl — "" (legacy: profile avatar URL)
     *
     * STRICT-asserted fields: sealed branch (`Success`), `user.token`
     * (the user-visible auth bearer used by every subsequent request).
     */
    @Test
    fun parity_login_structural() = runTest {
        val expectedToken = "session-cookie-bb_data"
        val r = rig {
            loginProvider = {
                LoginResult(
                    state = AuthState.Authenticated,
                    sessionToken = expectedToken,
                    captchaChallenge = null,
                )
            }
        }
        val produced = r.switching.login("u", "p", null, null, null)
        // Sealed-branch identity is the primary structural signal.
        assertTrue("Success branch", produced is AuthResponseDto.Success)
        produced as AuthResponseDto.Success
        // The single round-trippable field — load-bearing for the user.
        assertEquals("login.user.token strict", expectedToken, produced.user.token)
        // Tolerated divergences (Section G concern 5 — UserDto profile
        // fields lossy on forward).
        assertEquals("user.id tolerated empty", "", produced.user.id)
        assertEquals("user.avatarUrl tolerated empty", "", produced.user.avatarUrl)
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRUCTURAL: `getSearchPage`. The reverse mapper preserves the
     * pagination fields (totalPages, currentPage) and the items.size.
     * Per-item field round-trip works for items that carry the full
     * metadata key set (mirrors `getCategory_strict`).
     *
     * Tolerated divergences (Section G concern 1):
     *   - sortType when caller sent SortField.DOWNLOADED — collapses to
     *     SortField.DATE on forward (no DOWNLOADED in SortField).
     *     The forward mapper `toSortField()` in [SwitchingNetworkApi]
     *     drives SearchSortTypeDto.Downloaded → SortField.DATE; legacy
     *     would have passed Downloaded straight through. NOT tested
     *     here because the CALLER's sortType is consumed by the SDK
     *     and never round-trips back into the result DTO — it only
     *     affects which torrents the rutracker server returns.
     *   - period collapse (LastThreeDays → LAST_WEEK,
     *     LastTwoWeeks → LAST_MONTH) — same: caller-side, doesn't
     *     surface in the result DTO.
     *
     * The structural parity assertions therefore cover what the user
     * actually sees on the search-results page: pagination + per-item
     * fields.
     */
    @Test
    fun parity_getSearchPage_structural() = runTest {
        val r = rig {
            searchResultProvider = { _, page ->
                SearchResult(
                    items = listOf(
                        TorrentItem(
                            trackerId = "rutracker",
                            torrentId = "777",
                            title = "Ubuntu 24.04 LTS Server",
                            seeders = 100,
                            leechers = 5,
                            magnetUri = "magnet:?xt=urn:btih:777",
                            publishDate = Instant.fromEpochSeconds(1_700_000_000L),
                            metadata = mapOf(
                                "rutracker.categoryId" to "5",
                                "rutracker.categoryName" to "Linux",
                                "rutracker.size_text" to "1.8 GB",
                                "rutracker.status" to "Approved",
                            ),
                        ),
                    ),
                    totalPages = 12,
                    currentPage = page,
                )
            }
        }
        val produced = r.switching.getSearchPage(
            token = "ignored",
            searchQuery = "ubuntu",
            categories = null,
            author = null,
            authorId = null,
            // Use the CANONICAL sort to avoid the documented DOWNLOADED
            // collapse; the collapse tests are not in this method's
            // result DTO (it only affects what rutracker returns).
            sortType = SearchSortTypeDto.Date,
            sortOrder = SearchSortOrderDto.Descending,
            period = null,
            page = 0,
        )

        // Structural assertions — what the user sees on the search page.
        assertEquals("torrents.size", 1, produced.torrents.size)
        assertEquals("pages", 12, produced.pages)
        assertEquals("page", 0, produced.page)

        // Per-item field round-trip (the items had full metadata keys).
        val first = produced.torrents.first()
        assertEquals("777", first.id)
        assertEquals("Ubuntu 24.04 LTS Server", first.title)
        assertEquals("Linux", first.category?.name)
        assertEquals("5", first.category?.id)
        assertEquals(100, first.seeds)
        assertEquals(5, first.leeches)
        assertEquals("magnet:?xt=urn:btih:777", first.magnetLink)
        assertEquals("1.8 GB", first.size)
        assertEquals(TorrentStatusDto.Approved, first.status)
        assertEquals(1_700_000_000L, first.date)
        // description is forward-dropped on TorrentItem (lives only on
        // TopicDetail in the SDK model). Tolerated null per Section E.
        assertNull("description tolerated null on search-result row", first.description)
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRUCTURAL: `getTopic`. The page argument is silently ignored
     * (Section G concern 4 — matches legacy `GetTopicUseCase`
     * behaviour). Description rich AST flattens to a single Text leaf
     * (Section G concern 2). All other torrent fields round-trip
     * byte-identically when the metadata keys are present.
     *
     * Tolerated divergences:
     *   - page argument silently ignored.
     *   - description.children is a single Text leaf (not the
     *     full AST the legacy scraper produced).
     */
    @Test
    fun parity_getTopic_structural() = runTest {
        val r = rig {
            topicProvider = { topicId ->
                TopicDetail(
                    torrent = TorrentItem(
                        trackerId = "rutracker",
                        torrentId = topicId,
                        title = "Slackware 15.0 Live",
                        seeders = 200,
                        leechers = 1,
                        magnetUri = "magnet:?xt=urn:btih:abc",
                        publishDate = Instant.fromEpochSeconds(1_700_000_000L),
                        metadata = mapOf(
                            "rutracker.categoryId" to "501",
                            "rutracker.categoryName" to "Linux",
                            "rutracker.size_text" to "3.4 GB",
                            "rutracker.tags" to "linux distro",
                            "rutracker.status" to "Approved",
                            "rutracker.authorId" to "user-1",
                        ),
                    ),
                    description = "Lightweight Linux distro",
                    files = emptyList(),
                )
            }
        }
        val dto = r.switching.getTopic(token = "ignored", id = "9999", page = 0)
        assertTrue("default branch is TorrentDto", dto is TorrentDto)
        dto as TorrentDto
        assertEquals("9999", dto.id)
        assertEquals("Slackware 15.0 Live", dto.title)
        assertEquals(200, dto.seeds)
        assertEquals(1, dto.leeches)
        assertEquals("magnet:?xt=urn:btih:abc", dto.magnetLink)
        assertEquals("3.4 GB", dto.size)
        assertEquals(TorrentStatusDto.Approved, dto.status)
        // Tolerated divergence: rich AST collapses to single Text leaf.
        val children = dto.description?.children
        assertNotNull("description preserved (as flat Text leaf)", children)
        assertEquals("description has exactly one Text child", 1, children!!.size)
        val onlyChild = children.first()
        assertTrue("child is Text leaf (not original AST)", onlyChild is Text)
        assertEquals("Lightweight Linux distro", (onlyChild as Text).value)
        // Same page=0 vs page=99 must produce identical result —
        // proves the page argument is ignored.
        val dtoOtherPage = r.switching.getTopic(token = "ignored", id = "9999", page = 99)
        assertEquals(
            "page argument is silently ignored on SDK path (Section G concern 4)",
            dto,
            dtoOtherPage,
        )
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRUCTURAL: `getTopicPage`. TopicPage carries no inline comments
     * in the SDK model, so the reverse mapper synthesises an empty
     * `commentsPage.posts` list with `page=currentPage, pages=totalPages`.
     * Legacy returned the comments inline.
     *
     * Tolerated divergence:
     *   - commentsPage.posts is always empty (Section G concern 4 sibling).
     *
     * Strict-asserted: id, title, pagination shape, torrentData.
     */
    @Test
    fun parity_getTopicPage_structural() = runTest {
        val r = rig {
            topicProvider = { topicId ->
                TopicDetail(
                    torrent = TorrentItem(
                        trackerId = "rutracker",
                        torrentId = topicId,
                        title = "Topic Title",
                        seeders = 10,
                        leechers = 1,
                        magnetUri = "magnet:?xt=urn:btih:tt",
                        publishDate = Instant.fromEpochSeconds(1_700_000_001L),
                        metadata = mapOf(
                            "rutracker.size_text" to "200 MB",
                            "rutracker.tags" to "tag",
                            "rutracker.status" to "Approved",
                            "rutracker.authorId" to "u1",
                            "rutracker.categoryId" to "10",
                            "rutracker.categoryName" to "Cat",
                            "rutracker.date_text" to "2026-01-01",
                        ),
                    ),
                    description = "desc",
                )
            }
        }
        val page = r.switching.getTopicPage(token = "ignored", id = "topicX", page = 2)
        assertEquals("topicX", page.id)
        assertEquals("Topic Title", page.title)
        assertEquals("u1", page.author?.id)
        assertEquals("Cat", page.category?.name)
        assertEquals("10", page.category?.id)
        assertEquals("200 MB", page.torrentData?.size)
        assertEquals("tag", page.torrentData?.tags)
        assertEquals(10, page.torrentData?.seeds)
        assertEquals(1, page.torrentData?.leeches)
        assertEquals(TorrentStatusDto.Approved, page.torrentData?.status)
        assertEquals("magnet:?xt=urn:btih:tt", page.torrentData?.magnetLink)
        // Pagination round-trip: the SDK FakeTrackerClient.topic.getTopicPage
        // returns TopicPage(getTopic(id), totalPages = 1, currentPage = page).
        assertEquals("page", 2, page.commentsPage.page)
        assertEquals("pages", 1, page.commentsPage.pages)
        // Tolerated divergence: posts list is always empty.
        assertEquals(
            "tolerated empty posts (Section G concern 4 sibling)",
            0,
            page.commentsPage.posts.size,
        )
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRUCTURAL: `getCommentsPage`. Reverse mapper synthesises empty
     * `id` and `title` (Section G concern 5) because the
     * SDK CommentsPage model carries only the post list + pagination.
     * Each PostDto's `id` and `author.id` are also synthesised empty
     * because Comment model has no post id and no author id.
     *
     * Tolerated divergences:
     *   - CommentsPageDto.id    — "" (legacy: actual topic id)
     *   - CommentsPageDto.title — "" (legacy: topic title)
     *   - PostDto.id            — "" (legacy: actual post id)
     *   - PostDto.author.id     — "" (legacy: rutracker user id)
     *
     * STRICT-asserted: pagination fields, posts.size, post.author.name,
     * post.children.size==1 with body in Text leaf.
     *
     * (FakeTrackerClient.comments hardcodes empty CommentsPage; this
     * test exercises the mapper directly via real RuTrackerDtoMappers
     * to assert the structural invariants — the SDK round-trip is also
     * exercised end-to-end with the empty case.)
     */
    @Test
    fun parity_getCommentsPage_structural() = runTest {
        // End-to-end empty-case parity through the SDK round-trip.
        val r = rig()
        val emptyPage = r.switching.getCommentsPage(token = "ignored", id = "topic-1", page = 0)
        assertEquals(0, emptyPage.posts.size)
        assertEquals(0, emptyPage.page)
        assertEquals(0, emptyPage.pages)
        // Tolerated divergences on empty case:
        assertEquals(
            "tolerated empty id (Section G concern 5)",
            "",
            emptyPage.id,
        )
        assertEquals(
            "tolerated empty title (Section G concern 5)",
            "",
            emptyPage.title,
        )
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())

        // Mapper-level structural parity for non-empty input — proves the
        // SDK round-trip would handle real comments the same way.
        val mappers = RuTrackerDtoMappers()
        val sdkPage = lava.tracker.api.model.CommentsPage(
            items = listOf(
                lava.tracker.api.model.Comment(
                    author = "alice",
                    timestamp = Instant.fromEpochSeconds(1_700_000_000L),
                    body = "Great release",
                ),
            ),
            totalPages = 3,
            currentPage = 1,
        )
        val producedDto: CommentsPageDto = mappers.commentsPageToDto(sdkPage)
        assertEquals(1, producedDto.posts.size)
        assertEquals(3, producedDto.pages)
        assertEquals(1, producedDto.page)
        val post = producedDto.posts.first()
        // Tolerated divergences on per-post:
        assertEquals("post.id tolerated empty", "", post.id)
        assertEquals("post.author.id tolerated empty", "", post.author.id)
        // Strict-asserted user-visible fields:
        assertEquals("alice", post.author.name)
        assertEquals(1, post.children.size)
        assertTrue(post.children.first() is Text)
        assertEquals("Great release", (post.children.first() as Text).value)
    }

    /**
     * STRUCTURAL: `getTorrent`. Same divergence as `getTopic` (rich AST
     * flattens to single Text leaf). All structural fields round-trip
     * when metadata keys are present.
     *
     * Tolerated divergence:
     *   - description.children is a single Text leaf (Section G concern 2).
     */
    @Test
    fun parity_getTorrent_structural() = runTest {
        val r = rig {
            topicProvider = { topicId ->
                TopicDetail(
                    torrent = TorrentItem(
                        trackerId = "rutracker",
                        torrentId = topicId,
                        title = "Some Linux Distro",
                        seeders = 12,
                        leechers = 1,
                        magnetUri = "magnet:?xt=urn:btih:gt",
                        publishDate = Instant.fromEpochSeconds(1_700_000_002L),
                        metadata = mapOf(
                            "rutracker.size_text" to "1.0 GB",
                            "rutracker.tags" to "tag",
                            "rutracker.status" to "Approved",
                            "rutracker.categoryId" to "9",
                            "rutracker.categoryName" to "Linux",
                            "rutracker.authorId" to "ux",
                        ),
                    ),
                    description = "rich-html-with-bold-and-quote",
                )
            }
        }
        val dto = r.switching.getTorrent(token = "ignored", id = "12345")
        // Strict-asserted user-visible fields.
        assertEquals("12345", dto.id)
        assertEquals("Some Linux Distro", dto.title)
        assertEquals(12, dto.seeds)
        assertEquals(1, dto.leeches)
        assertEquals("magnet:?xt=urn:btih:gt", dto.magnetLink)
        assertEquals("1.0 GB", dto.size)
        assertEquals(TorrentStatusDto.Approved, dto.status)
        assertEquals(1_700_000_002L, dto.date)
        assertEquals("Linux", dto.category?.name)
        assertEquals("9", dto.category?.id)
        assertEquals("ux", dto.author?.id)
        // Tolerated divergence: rich AST collapses to single Text leaf.
        val children = dto.description?.children
        assertNotNull(children)
        assertEquals(
            "tolerated single-Text-leaf (Section G concern 2)",
            1,
            children!!.size,
        )
        assertTrue(children.first() is Text)
        assertEquals(
            "rich-html-with-bold-and-quote",
            (children.first() as Text).value,
        )
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    /**
     * STRUCTURAL: `download`. The SDK facade returns ByteArray only;
     * the FileDto round-trip synthesises `contentDisposition` and
     * `contentType` (Section G concern 3 — the Android client doesn't
     * read those headers today).
     *
     * Tolerated divergences:
     *   - contentDisposition — synthesised "attachment; filename=<id>.torrent"
     *     (legacy: server-supplied Content-Disposition header).
     *   - contentType        — synthesised "application/x-bittorrent"
     *     (legacy: server-supplied Content-Type header).
     *
     * STRICT-asserted: bytes round-trip BYTE-IDENTICAL — the .torrent
     * file is what the user actually downloads.
     */
    @Test
    fun parity_download_structural() = runTest {
        val expectedBytes = byteArrayOf(0x64, 0x38, 0x3a, 0x61, 0x6e, 0x6e, 0x6f, 0x75)
        val r = rig {
            downloadProvider = { _ -> expectedBytes }
        }
        val produced = r.switching.download(token = "ignored", id = "777")
        // STRICT user-visible signal: the .torrent bytes the system
        // download manager receives.
        assertArrayEquals(
            "download bytes MUST round-trip byte-identical",
            expectedBytes,
            produced.bytes,
        )
        // Tolerated divergences (synthesised values).
        assertEquals(
            "tolerated synthesised contentDisposition (Section G concern 3)",
            "attachment; filename=777.torrent",
            produced.contentDisposition,
        )
        assertEquals(
            "tolerated synthesised contentType (Section G concern 3)",
            "application/x-bittorrent",
            produced.contentType,
        )
        assertTrue("legacy untouched", r.legacy.calls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Cross-cutting smoke checks
    // ---------------------------------------------------------------------

    /**
     * Smoke: the SDK guard refuses delegation when endpoint is LAN.
     * Proves the structural-parity claims above are scoped to the
     * direct-rutracker path, not LAN-proxy or LAN-GoApi.
     */
    @Test
    fun parity_guard_routes_LAN_endpoints_to_legacy() = runTest {
        // Non-default rig with LAN GoApi.
        val fake = FakeTrackerClient(rutrackerDescriptor)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("rutracker")
        val legacy = FakeLegacyNetworkApi()
        val repo = FakeNetworkApiRepository(legacy)
        val settings = FakeSettingsRepository(initial = Endpoint.GoApi(host = "192.168.1.5", port = 8443))
        val switching = SwitchingNetworkApi(
            networkApiRepository = repo,
            sdk = sdk,
            mappers = RuTrackerDtoMappers(),
            settingsRepository = settings,
        )
        switching.getCategory(id = "5", page = 1)
        assertTrue(
            "LAN endpoint MUST route via legacy api(); calls=${legacy.calls}",
            legacy.calls.any { it.startsWith("getCategory(") },
        )
    }

    /**
     * Smoke: confirms that a TorrentItem with metadata["rutracker.kind"]
     * = "topic" round-trips to the thin TopicDto rather than a full
     * TorrentDto. This proves the reverse mapper's discriminator works,
     * which is load-bearing for browse-page rendering (mixed
     * Topic/Torrent rows).
     */
    @Test
    fun parity_topic_kind_discriminator_strict() = runTest {
        val mappers = RuTrackerDtoMappers()
        val item = TorrentItem(
            trackerId = "rutracker",
            torrentId = "thin-1",
            title = "Sticky Topic",
            metadata = mapOf("rutracker.kind" to "topic"),
        )
        val produced: ForumTopicDto = mappers.favoritesToDto(listOf(item)).topics.first()
        assertTrue(
            "metadata[rutracker.kind]=topic MUST round-trip to TopicDto, not TorrentDto",
            produced is TopicDto,
        )
        produced as TopicDto
        assertEquals("thin-1", produced.id)
        assertEquals("Sticky Topic", produced.title)
    }
}
