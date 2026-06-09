package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.data.api.service.TopicService
import lava.models.Page
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.models.topic.TorrentData
import lava.testing.TestDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [GetTopicUseCase] wired to the REAL [VisitTopicUseCase]
 * implementation (NOT a mock) plus behaviorally-equivalent in-memory fakes for
 * [VisitedRepository] / [FavoritesRepository] and a fake [TopicService] at the
 * outermost boundary.
 *
 * The shared :core:testing TestVisitedRepository / TestFavoritesRepository are
 * TODO("Not yet implemented") stubs (a parallel agent owns the fix), so this
 * test defines local in-memory fakes that DO persist — the user-visible "topic
 * is now in Visited history" outcome is the primary assertion.
 *
 * Bluff-Audit (§6.J clause 2 falsifiability), see FALSIFIABILITY REHEARSAL note
 * at the bottom of this file.
 */
class GetTopicUseCaseTest {

    /** In-memory VisitedRepository fake — really stores added topics. */
    private class InMemoryVisitedRepository : VisitedRepository {
        val added = mutableListOf<TopicPage>()
        override fun observeTopics(): Flow<List<Topic>> = MutableStateFlow(emptyList())
        override fun observeIds(): Flow<List<String>> =
            MutableStateFlow(added.map { it.id })
        override suspend fun add(topic: TopicPage) { added += topic }
        override suspend fun clear() { added.clear() }
    }

    /** In-memory FavoritesRepository fake — records markVisited calls. */
    private class InMemoryFavoritesRepository : FavoritesRepository {
        val visitedIds = mutableListOf<String>()
        override fun observeTopics(): Flow<List<TopicModel<out Topic>>> =
            MutableStateFlow(emptyList())
        override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override fun observeUpdatedIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun getIds(): List<String> = emptyList()
        override suspend fun getTorrents(): List<Torrent> = emptyList()
        override suspend fun contains(id: String): Boolean = false
        override suspend fun add(topic: Topic) = Unit
        override suspend fun add(topics: List<Topic>) = Unit
        override suspend fun remove(topic: Topic) = Unit
        override suspend fun remove(topics: List<Topic>) = Unit
        override suspend fun removeById(id: String) = Unit
        override suspend fun removeById(ids: List<String>) = Unit
        override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
        override suspend fun markVisited(id: String) { visitedIds += id }
        override suspend fun clear() = Unit
    }

    /** Fake TopicService at the network boundary — returns a canned page per id. */
    private class FakeTopicService(private val pages: Map<String, TopicPage>) : TopicService {
        override suspend fun getTopic(id: String): Topic =
            throw UnsupportedOperationException("not used by GetTopicUseCase")
        override suspend fun getTopicPage(id: String, page: Int?): TopicPage =
            pages.getValue(id)
        override suspend fun getCommentsPage(id: String, page: Int): Page<Post> =
            throw UnsupportedOperationException("not used by GetTopicUseCase")
        override suspend fun addComment(topicId: String, message: String): Boolean =
            throw UnsupportedOperationException("not used by GetTopicUseCase")
    }

    private fun topicPage(id: String, magnet: String?) = TopicPage(
        id = id,
        title = "Topic $id",
        author = null,
        category = null,
        torrentData = TorrentData(
            tags = null,
            posterUrl = null,
            status = null,
            date = null,
            size = "1 GB",
            seeds = 10,
            leeches = 2,
            magnetLink = magnet,
        ),
        commentsPage = Page(items = emptyList(), page = 1, pages = 1),
    )

    private fun build(
        service: TopicService,
        visited: VisitedRepository,
        favorites: FavoritesRepository,
    ): GetTopicUseCase {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        return GetTopicUseCase(
            topicService = service,
            visitTopicUseCase = VisitTopicUseCase(visited, favorites, dispatchers),
            dispatchers = dispatchers,
        )
    }

    @Test
    fun `returns the topic page carrying the correct magnet link`() = runTest {
        val magnet = "magnet:?xt=urn:btih:ABCDEF0123456789"
        val service = FakeTopicService(mapOf("42" to topicPage("42", magnet)))
        val useCase = build(service, InMemoryVisitedRepository(), InMemoryFavoritesRepository())

        val result = useCase("42")

        assertEquals("42", result.id)
        assertEquals(magnet, result.torrentData?.magnetLink)
    }

    @Test
    fun `honest null magnet when the topic has none`() = runTest {
        val service = FakeTopicService(mapOf("7" to topicPage("7", magnet = null)))
        val useCase = build(service, InMemoryVisitedRepository(), InMemoryFavoritesRepository())

        val result = useCase("7")

        assertNull(result.torrentData?.magnetLink)
    }

    @Test
    fun `opening a topic persists it into Visited history and marks it visited`() = runTest {
        val visited = InMemoryVisitedRepository()
        val favorites = InMemoryFavoritesRepository()
        val service = FakeTopicService(mapOf("99" to topicPage("99", "magnet:?xt=urn:btih:DEAD")))
        val useCase = build(service, visited, favorites)

        useCase("99")

        // Primary user-visible side effects: the topic now appears in Visited
        // history and is flagged visited in Favorites.
        assertEquals(listOf("99"), visited.added.map { it.id })
        assertTrue(favorites.visitedIds.contains("99"))
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation A — GetTopicUseCase.invoke: drop the `.also { visitTopicUseCase(it) }`
 *   call (return topicService.getTopicPage(id) only).
 *   Observed failure: `opening a topic persists it into Visited history...`
 *   FAILED — `expected:<[99]> but was:<[]>` (visited.added empty).
 *
 * Mutation B — VisitTopicUseCase.invoke: remove `favoritesRepository.markVisited(topic.id)`.
 *   Observed failure: same test FAILED on `assertTrue(favorites.visitedIds.contains("99"))`.
 *
 * Both mutations reverted; suite re-run green. See agent report for verbatim output.
 */
