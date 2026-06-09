package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.models.Page
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.testing.TestDispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real-stack test for [VisitTopicUseCase] (the visited-record slice). The SUT is
 * a REAL [VisitTopicUseCase]; only the two repository boundaries are faked, with
 * local in-memory fakes that really persist (the shared :core:testing fakes are
 * TODO-throw stubs owned by a parallel agent).
 *
 * Primary assertion: after a visit, the topic is in Visited history AND flagged
 * visited in Favorites — exactly the two user-visible state changes the user
 * relies on (a topic shows up under "Visited" and loses its "new" highlight).
 *
 * FALSIFIABILITY REHEARSAL block at the bottom of this file.
 */
class VisitTopicUseCaseTest {

    private class InMemoryVisitedRepository : VisitedRepository {
        val added = mutableListOf<TopicPage>()
        override fun observeTopics(): Flow<List<Topic>> = MutableStateFlow(emptyList())
        override fun observeIds(): Flow<List<String>> =
            MutableStateFlow(added.map { it.id })
        override fun observeProviderIds(): Flow<Map<String, String?>> = MutableStateFlow(emptyMap())
        override suspend fun add(topic: TopicPage, providerId: String?) { added += topic }
        override suspend fun clear() { added.clear() }
    }

    private class InMemoryFavoritesRepository : FavoritesRepository {
        val visitedIds = mutableListOf<String>()
        override fun observeTopics(): Flow<List<TopicModel<out Topic>>> =
            MutableStateFlow(emptyList())
        override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override fun observeUpdatedIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun getIds(): List<String> = emptyList()
        override suspend fun getTorrents(): List<Torrent> = emptyList()
        override suspend fun contains(id: String): Boolean = false
        override suspend fun add(topic: Topic, providerId: String?) = Unit
        override suspend fun add(topics: List<Topic>) = Unit
        override suspend fun remove(topic: Topic) = Unit
        override suspend fun remove(topics: List<Topic>) = Unit
        override suspend fun removeById(id: String) = Unit
        override suspend fun removeById(ids: List<String>) = Unit
        override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
        override suspend fun markVisited(id: String) { visitedIds += id }
        override suspend fun clear() = Unit
    }

    private fun topicPage(id: String) = TopicPage(
        id = id,
        title = "Topic $id",
        author = null,
        category = null,
        torrentData = null,
        commentsPage = Page(items = emptyList<Post>(), page = 1, pages = 1),
    )

    @Test
    fun `visiting a topic records it in history and marks it visited`() = runTest {
        val visited = InMemoryVisitedRepository()
        val favorites = InMemoryFavoritesRepository()
        val useCase = VisitTopicUseCase(
            visitedRepository = visited,
            favoritesRepository = favorites,
            dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
        )

        useCase(topicPage("123"))

        assertEquals(listOf("123"), visited.added.map { it.id })
        assertEquals(listOf("123"), favorites.visitedIds)
    }

    @Test
    fun `visiting the same topic twice records both visits (no swallowing)`() = runTest {
        val visited = InMemoryVisitedRepository()
        val favorites = InMemoryFavoritesRepository()
        val useCase = VisitTopicUseCase(
            visitedRepository = visited,
            favoritesRepository = favorites,
            dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
        )

        useCase(topicPage("5"))
        useCase(topicPage("5"))

        assertEquals(2, visited.added.size)
        assertEquals(listOf("5", "5"), favorites.visitedIds)
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation A — VisitTopicUseCase: remove `visitedRepository.add(topic)`.
 *   Observed failure: `visiting a topic records it in history...` FAILED
 *   — `expected:<[123]> but was:<[]>`.
 *
 * Mutation B — VisitTopicUseCase: remove `favoritesRepository.markVisited(topic.id)`.
 *   Observed failure: same test FAILED on the favorites.visitedIds assertion
 *   — `expected:<[123]> but was:<[]>`.
 *
 * Both mutations reverted; suite re-run green. See agent report for verbatim output.
 */
