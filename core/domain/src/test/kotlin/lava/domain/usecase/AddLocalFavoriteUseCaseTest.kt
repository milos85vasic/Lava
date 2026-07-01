package lava.domain.usecase

import kotlinx.coroutines.test.runTest
import lava.data.api.service.TopicService
import lava.models.Page
import lava.models.topic.BaseTopic
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.testing.repository.TestFavoritesRepository
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [AddLocalFavoriteUseCase]. The SUT is the real use case;
 * the favorites store is the REAL [TestFavoritesRepository] (a behaviorally-
 * equivalent fake of FavoritesRepositoryImpl, itself guarded by
 * TestFavoritesRepositoryEquivalenceTest). Only the network boundary
 * [TopicService] is faked.
 *
 * User-visible feature covered: tapping "add to favorites" on a topic. The use
 * case fetches the topic from the service and persists it locally, so it appears
 * on the Favorites screen. Primary assertions are on the PERSISTED favorites
 * state (contains / getIds) — what the Favorites screen renders — never on call
 * counts. Bluff-Audit: see FALSIFIABILITY REHEARSAL at the bottom.
 */
class AddLocalFavoriteUseCaseTest {

    private class FakeTopicService(private val topicsById: Map<String, Topic>) : TopicService {
        override suspend fun getTopic(id: String): Topic =
            topicsById[id] ?: error("FakeTopicService has no topic for id=$id")

        override suspend fun getTopicPage(id: String, page: Int?, providerId: String?): TopicPage =
            throw UnsupportedOperationException("not used by AddLocalFavoriteUseCase")

        override suspend fun getCommentsPage(id: String, page: Int): Page<Post> =
            throw UnsupportedOperationException("not used by AddLocalFavoriteUseCase")

        override suspend fun addComment(topicId: String, message: String): Boolean =
            throw UnsupportedOperationException("not used by AddLocalFavoriteUseCase")
    }

    @Test
    fun `adding a local favorite fetches the topic and persists it`() = runTest {
        val favorites = TestFavoritesRepository()
        val service = FakeTopicService(mapOf("42" to BaseTopic(id = "42", title = "The Matrix")))
        val useCase = AddLocalFavoriteUseCase(service, favorites, testDispatchers())

        useCase("42", providerId = "rutracker")

        // User-visible: the tapped topic is now in the user's favorites.
        assertTrue("the favorite must be persisted after add", favorites.contains("42"))
        assertEquals(listOf("42"), favorites.getIds())
    }

    @Test
    fun `only the requested topic id is saved`() = runTest {
        val favorites = TestFavoritesRepository()
        val service = FakeTopicService(
            mapOf("1" to BaseTopic(id = "1", title = "A"), "2" to BaseTopic(id = "2", title = "B")),
        )
        val useCase = AddLocalFavoriteUseCase(service, favorites, testDispatchers())

        useCase("2")

        // The favorite saved is exactly the one the user tapped — not some other topic.
        assertEquals(listOf("2"), favorites.getIds())
        assertTrue(favorites.contains("2"))
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation — AddLocalFavoriteUseCase.invoke: pass the wrong id to the service,
 *   e.g. change `topicService.getTopic(id)` to `topicService.getTopic("nope")`,
 *   so the fetched/persisted topic no longer matches the user's tapped id.
 *   Observed failure: `only the requested topic id is saved` FAILS — the fake
 *   service errors on the unknown id (or persists the wrong topic), so
 *   favorites.getIds() != [2]. Equivalently, dropping `favoritesRepository.add(...)`
 *   makes `adding a local favorite ...` FAIL with contains("42") == false.
 *   Reverted: yes — suite re-run green.
 */
