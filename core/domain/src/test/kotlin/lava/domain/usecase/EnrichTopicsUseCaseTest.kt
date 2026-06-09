package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.data.api.repository.BookmarksRepository
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.models.forum.Category
import lava.models.forum.CategoryModel
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack unit test for [EnrichTopicsUseCase].
 *
 * SUT is the production [EnrichTopicsUseCase] (no mocking of the SUT). Boundary
 * repositories are behavioral fakes backed by [MutableStateFlow] that expose the
 * exact `observe*` surfaces the production `combine` consumes. Primary assertions
 * are on the user-visible mapped [TopicModel] flags (isFavorite / isVisited /
 * isNew / hasUpdate) — the badges a real user sees on each topic row.
 *
 * FALSIFIABILITY REHEARSAL: replaced `isFavorite = favoriteTopics.contains(topic.id)`
 * with `isFavorite = false` in EnrichTopicsUseCase. The "marks favorite / visited /
 * new / updated topics by id" test FAILED with:
 *   java.lang.AssertionError: expected:<true> but was:<false>
 * Reverted; test passes.
 */
class EnrichTopicsUseCaseTest {

    private class FakeFavoritesRepository(
        ids: List<String>,
        updatedIds: List<String>,
    ) : FavoritesRepository {
        private val idsFlow = MutableStateFlow(ids)
        private val updatedFlow = MutableStateFlow(updatedIds)
        override fun observeIds(): Flow<List<String>> = idsFlow
        override fun observeUpdatedIds(): Flow<List<String>> = updatedFlow
        override fun observeTopics(): Flow<List<TopicModel<out Topic>>> =
            throw UnsupportedOperationException("not used by EnrichTopicsUseCase")
        override suspend fun getIds(): List<String> = idsFlow.value
        override suspend fun getTorrents(): List<Torrent> = emptyList()
        override suspend fun contains(id: String): Boolean = id in idsFlow.value
        override suspend fun add(topic: Topic, providerId: String?) = Unit
        override suspend fun add(topics: List<Topic>) = Unit
        override suspend fun remove(topic: Topic) = Unit
        override suspend fun remove(topics: List<Topic>) = Unit
        override suspend fun removeById(id: String) = Unit
        override suspend fun removeById(ids: List<String>) = Unit
        override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
        override suspend fun markVisited(id: String) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeVisitedRepository(ids: List<String>) : VisitedRepository {
        private val idsFlow = MutableStateFlow(ids)
        override fun observeIds(): Flow<List<String>> = idsFlow
        override fun observeTopics(): Flow<List<Topic>> =
            throw UnsupportedOperationException("not used by EnrichTopicsUseCase")
        override fun observeProviderIds(): Flow<Map<String, String?>> = MutableStateFlow(emptyMap())
        override suspend fun add(topic: TopicPage, providerId: String?) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeBookmarksRepository(newTopics: List<String>) : BookmarksRepository {
        private val newTopicsFlow = MutableStateFlow(newTopics)
        override fun observeNewTopics(): Flow<List<String>> = newTopicsFlow
        override fun observeBookmarks(): Flow<List<CategoryModel>> =
            throw UnsupportedOperationException("not used by EnrichTopicsUseCase")
        override fun observeIds(): Flow<List<String>> =
            throw UnsupportedOperationException("not used by EnrichTopicsUseCase")
        override fun observeNewTopics(id: String): Flow<List<String>> =
            throw UnsupportedOperationException("not used by EnrichTopicsUseCase")
        override suspend fun getAllBookmarks(): List<Category> = emptyList()
        override suspend fun getTopics(id: String): List<String> = emptyList()
        override suspend fun getNewTopics(id: String): List<String> = newTopicsFlow.value
        override suspend fun isBookmark(id: String): Boolean = false
        override suspend fun add(category: Category) = Unit
        override suspend fun remove(id: String) = Unit
        override suspend fun update(id: String, topics: List<String>, newTopics: List<String>) = Unit
        override suspend fun clear() = Unit
    }

    private fun topic(id: String) = BaseTopic(id = id, title = "title-$id")

    @Test
    fun `marks favorite visited new and updated topics by id`() = runBlocking {
        val useCase = EnrichTopicsUseCase(
            bookmarksRepository = FakeBookmarksRepository(newTopics = listOf("3")),
            favoritesRepository = FakeFavoritesRepository(
                ids = listOf("1"),
                updatedIds = listOf("4"),
            ),
            visitedRepository = FakeVisitedRepository(ids = listOf("2")),
        )

        val result = useCase(listOf(topic("1"), topic("2"), topic("3"), topic("4"), topic("5")))
            .first()

        assertEquals(5, result.size)
        // Topic 1 -> favorite only
        result[0].let {
            assertTrue("topic 1 must be favorite", it.isFavorite)
            assertFalse(it.isVisited)
            assertFalse(it.isNew)
            assertFalse(it.hasUpdate)
        }
        // Topic 2 -> visited only
        result[1].let {
            assertTrue("topic 2 must be visited", it.isVisited)
            assertFalse(it.isFavorite)
            assertFalse(it.isNew)
            assertFalse(it.hasUpdate)
        }
        // Topic 3 -> new only
        result[2].let {
            assertTrue("topic 3 must be new", it.isNew)
            assertFalse(it.isFavorite)
            assertFalse(it.isVisited)
            assertFalse(it.hasUpdate)
        }
        // Topic 4 -> hasUpdate only
        result[3].let {
            assertTrue("topic 4 must have update", it.hasUpdate)
            assertFalse(it.isFavorite)
            assertFalse(it.isVisited)
            assertFalse(it.isNew)
        }
        // Topic 5 -> none
        result[4].let {
            assertFalse(it.isFavorite)
            assertFalse(it.isVisited)
            assertFalse(it.isNew)
            assertFalse(it.hasUpdate)
        }
    }

    @Test
    fun `preserves topic order and identity in emitted models`() = runBlocking {
        val useCase = EnrichTopicsUseCase(
            bookmarksRepository = FakeBookmarksRepository(newTopics = emptyList()),
            favoritesRepository = FakeFavoritesRepository(ids = emptyList(), updatedIds = emptyList()),
            visitedRepository = FakeVisitedRepository(ids = emptyList()),
        )

        val input = listOf(topic("a"), topic("b"), topic("c"))
        val result = useCase(input).first()

        assertEquals(listOf("a", "b", "c"), result.map { it.topic.id })
        assertEquals(input, result.map { it.topic })
    }

    @Test
    fun `same id appearing in multiple sources sets multiple flags`() = runBlocking {
        val useCase = EnrichTopicsUseCase(
            bookmarksRepository = FakeBookmarksRepository(newTopics = listOf("x")),
            favoritesRepository = FakeFavoritesRepository(ids = listOf("x"), updatedIds = listOf("x")),
            visitedRepository = FakeVisitedRepository(ids = listOf("x")),
        )

        val model = useCase(listOf(topic("x"))).first().single()

        assertTrue(model.isFavorite)
        assertTrue(model.isVisited)
        assertTrue(model.isNew)
        assertTrue(model.hasUpdate)
    }

    @Test
    fun `empty topic list emits empty model list`() = runBlocking {
        val useCase = EnrichTopicsUseCase(
            bookmarksRepository = FakeBookmarksRepository(newTopics = listOf("1")),
            favoritesRepository = FakeFavoritesRepository(ids = listOf("1"), updatedIds = listOf("1")),
            visitedRepository = FakeVisitedRepository(ids = listOf("1")),
        )

        val result = useCase(emptyList<Topic>()).first()

        assertTrue(result.isEmpty())
    }
}
