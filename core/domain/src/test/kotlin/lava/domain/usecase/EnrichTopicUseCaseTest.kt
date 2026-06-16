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
 * Real-stack unit test for [EnrichTopicUseCase] (the SINGULAR variant — enriches one
 * topic into a [TopicModel], used by the topic-detail screen; distinct from the
 * already-tested plural [EnrichTopicsUseCase]).
 *
 * SUT is the production [EnrichTopicUseCase] (no mocking of the SUT). Boundary
 * repositories are behavioral fakes backed by [MutableStateFlow] that expose the
 * exact `observe*` surfaces the production `combine` consumes. Primary assertions
 * are on the user-visible mapped [TopicModel] flags (isFavorite / isVisited /
 * isNew / hasUpdate) — the badges a real user sees on the topic-detail screen.
 *
 * FALSIFIABILITY REHEARSAL (mutation targets the production combine lambda in
 * EnrichTopicUseCase): replaced
 *   isVisited = visitedTopics.contains(topic.id)
 * with
 *   isVisited = false
 * The "marks single topic visited by id" test FAILED with:
 *   java.lang.AssertionError: matched topic must be visited expected:<true> but was:<false>
 * Reverted; test passes.
 */
class EnrichTopicUseCaseTest {

    private class FakeFavoritesRepository(
        ids: List<String>,
        updatedIds: List<String>,
    ) : FavoritesRepository {
        private val idsFlow = MutableStateFlow(ids)
        private val updatedFlow = MutableStateFlow(updatedIds)
        override fun observeIds(): Flow<List<String>> = idsFlow
        override fun observeUpdatedIds(): Flow<List<String>> = updatedFlow
        override fun observeTopics(): Flow<List<TopicModel<out Topic>>> =
            throw UnsupportedOperationException("not used by EnrichTopicUseCase")
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
            throw UnsupportedOperationException("not used by EnrichTopicUseCase")
        override fun observeProviderIds(): Flow<Map<String, String?>> = MutableStateFlow(emptyMap())
        override suspend fun add(topic: TopicPage, providerId: String?) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeBookmarksRepository(newTopics: List<String>) : BookmarksRepository {
        private val newTopicsFlow = MutableStateFlow(newTopics)
        override fun observeNewTopics(): Flow<List<String>> = newTopicsFlow
        override fun observeBookmarks(): Flow<List<CategoryModel>> =
            throw UnsupportedOperationException("not used by EnrichTopicUseCase")
        override fun observeIds(): Flow<List<String>> =
            throw UnsupportedOperationException("not used by EnrichTopicUseCase")
        override fun observeNewTopics(id: String): Flow<List<String>> =
            throw UnsupportedOperationException("not used by EnrichTopicUseCase")
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
    fun `marks single topic visited by id`() = runBlocking {
        val useCase = EnrichTopicUseCase(
            favoritesRepository = FakeFavoritesRepository(ids = emptyList(), updatedIds = emptyList()),
            visitedRepository = FakeVisitedRepository(ids = listOf("42")),
            bookmarksRepository = FakeBookmarksRepository(newTopics = emptyList()),
        )

        val model = useCase(topic("42")).first()

        assertTrue("matched topic must be visited", model.isVisited)
        assertFalse(model.isFavorite)
        assertFalse(model.isNew)
        assertFalse(model.hasUpdate)
        assertEquals("42", model.topic.id)
    }

    @Test
    fun `each badge flag is driven by its own source independently`() = runBlocking {
        // favorite -> "f", updated -> "u", visited -> "v", new -> "n".
        // The enriched topic "f" must show ONLY the favorite badge; the use case must
        // not leak another source's membership into a topic that is not in that source.
        val useCase = EnrichTopicUseCase(
            favoritesRepository = FakeFavoritesRepository(ids = listOf("f"), updatedIds = listOf("u")),
            visitedRepository = FakeVisitedRepository(ids = listOf("v")),
            bookmarksRepository = FakeBookmarksRepository(newTopics = listOf("n")),
        )

        val favoriteModel = useCase(topic("f")).first()
        assertTrue("topic f must be favorite", favoriteModel.isFavorite)
        assertFalse("topic f must not be visited", favoriteModel.isVisited)
        assertFalse("topic f must not be new", favoriteModel.isNew)
        assertFalse("topic f must not have update", favoriteModel.hasUpdate)

        val updatedModel = useCase(topic("u")).first()
        assertTrue("topic u must have update", updatedModel.hasUpdate)
        assertFalse("topic u must not be favorite", updatedModel.isFavorite)
        assertFalse("topic u must not be visited", updatedModel.isVisited)
        assertFalse("topic u must not be new", updatedModel.isNew)

        val unmatchedModel = useCase(topic("none")).first()
        assertFalse(unmatchedModel.isFavorite)
        assertFalse(unmatchedModel.isVisited)
        assertFalse(unmatchedModel.isNew)
        assertFalse(unmatchedModel.hasUpdate)
    }

    @Test
    fun `same id present in all four sources sets all flags and preserves topic identity`() = runBlocking {
        val useCase = EnrichTopicUseCase(
            favoritesRepository = FakeFavoritesRepository(ids = listOf("x"), updatedIds = listOf("x")),
            visitedRepository = FakeVisitedRepository(ids = listOf("x")),
            bookmarksRepository = FakeBookmarksRepository(newTopics = listOf("x")),
        )

        val input = topic("x")
        val model = useCase(input).first()

        assertTrue(model.isFavorite)
        assertTrue(model.isVisited)
        assertTrue(model.isNew)
        assertTrue(model.hasUpdate)
        // The singular use case must preserve the concrete topic instance/type T.
        assertEquals(input, model.topic)
    }
}
