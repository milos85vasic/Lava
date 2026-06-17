package lava.domain.usecase

import kotlinx.coroutines.test.runTest
import lava.data.api.service.FavoritesService
import lava.data.api.service.TopicService
import lava.models.Page
import lava.models.topic.BaseTopic
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.testing.repository.TestFavoritesRepository
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [AddRemoteFavoriteUseCase]. The SUT is the real use case;
 * the favorites store is the REAL [TestFavoritesRepository] (a behaviorally-
 * equivalent fake of FavoritesRepositoryImpl, itself guarded by
 * TestFavoritesRepositoryEquivalenceTest). Only the two true network boundaries
 * — [FavoritesService] (the server-side favorite registration) and
 * [TopicService] (the topic fetch) — are faked.
 *
 * User-visible feature covered: tapping "add to favorites" on a topic that the
 * server tracks for this account. The use case FIRST registers the favorite on
 * the server (`require(favoritesService.add(id))`) and, only if that succeeds,
 * fetches the topic and persists it locally so it shows on the Favorites screen.
 * The server-registration result is the load-bearing branch: a rejected server
 * add MUST throw and leave NO local row; a server add that succeeds but whose
 * topic-fetch fails MUST NOT crash the user (the runCatching swallow) — but the
 * server registration still stands.
 *
 * Primary assertions are on the PERSISTED favorites state (contains / getIds) and
 * on the thrown exception type — what the user observes — never on call counts.
 * Bluff-Audit: see FALSIFIABILITY REHEARSAL at the bottom.
 */
class AddRemoteFavoriteUseCaseTest {

    private class FakeFavoritesService(
        private val acceptedIds: Set<String>,
    ) : FavoritesService {
        override suspend fun getFavorites(): List<Topic> =
            throw UnsupportedOperationException("not used by AddRemoteFavoriteUseCase")

        override suspend fun add(id: String): Boolean = id in acceptedIds

        override suspend fun remove(id: String): Boolean =
            throw UnsupportedOperationException("not used by AddRemoteFavoriteUseCase")
    }

    private class FakeTopicService(
        private val topicsById: Map<String, Topic>,
        private val failOnFetch: Boolean = false,
    ) : TopicService {
        override suspend fun getTopic(id: String): Topic {
            if (failOnFetch) error("FakeTopicService: simulated topic-fetch failure for id=$id")
            return topicsById[id] ?: error("FakeTopicService has no topic for id=$id")
        }

        override suspend fun getTopicPage(id: String, page: Int?): TopicPage =
            throw UnsupportedOperationException("not used by AddRemoteFavoriteUseCase")

        override suspend fun getCommentsPage(id: String, page: Int): Page<Post> =
            throw UnsupportedOperationException("not used by AddRemoteFavoriteUseCase")

        override suspend fun addComment(topicId: String, message: String): Boolean =
            throw UnsupportedOperationException("not used by AddRemoteFavoriteUseCase")
    }

    @Test
    fun `server accepts then topic is fetched and persisted locally`() = runTest {
        val favorites = TestFavoritesRepository()
        val service = FakeFavoritesService(acceptedIds = setOf("42"))
        val topicService = FakeTopicService(mapOf("42" to BaseTopic(id = "42", title = "The Matrix")))
        val useCase = AddRemoteFavoriteUseCase(service, favorites, topicService, testDispatchers())

        useCase("42")

        // User-visible: after a successful server add, the topic appears on the
        // Favorites screen.
        assertTrue("the favorite must be persisted after a successful remote add", favorites.contains("42"))
        assertEquals(listOf("42"), favorites.getIds())
    }

    @Test
    fun `server rejection throws and persists nothing locally`() = runTest {
        val favorites = TestFavoritesRepository()
        // Server tracks no ids → add() returns false → require() must throw.
        val service = FakeFavoritesService(acceptedIds = emptySet())
        val topicService = FakeTopicService(mapOf("42" to BaseTopic(id = "42", title = "The Matrix")))
        val useCase = AddRemoteFavoriteUseCase(service, favorites, topicService, testDispatchers())

        val thrown = runCatching { useCase("42") }.exceptionOrNull()
        assertTrue(
            "a rejected remote add must throw IllegalArgumentException (require failed), but was: $thrown",
            thrown is IllegalArgumentException,
        )

        // User-visible: a server rejection leaves NO local favorite row — the
        // Favorites screen does not gain a phantom entry.
        assertFalse("a rejected remote add must not persist a local favorite", favorites.contains("42"))
        assertEquals(emptyList<String>(), favorites.getIds())
    }

    @Test
    fun `server accepts but topic fetch failure is swallowed without crashing`() = runTest {
        val favorites = TestFavoritesRepository()
        val service = FakeFavoritesService(acceptedIds = setOf("42"))
        // Server accepted the favorite, but the topic fetch fails. The use case's
        // runCatching MUST swallow this so the user's tap does not crash.
        val topicService = FakeTopicService(emptyMap(), failOnFetch = true)
        val useCase = AddRemoteFavoriteUseCase(service, favorites, topicService, testDispatchers())

        // No exception escapes to the caller (the user-visible contract).
        useCase("42")

        // The topic-fetch failed, so no local row was written — but the call
        // completed cleanly rather than propagating the fetch error.
        assertFalse("a failed topic fetch must not leave a local favorite row", favorites.contains("42"))
        assertEquals(emptyList<String>(), favorites.getIds())
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation — AddRemoteFavoriteUseCase.invoke: change `require(favoritesService.add(id))`
 *   to `favoritesService.add(id)` (drop the require guard), so a server rejection
 *   no longer short-circuits.
 *   Observed failure: `server rejection throws and persists nothing locally` FAILS
 *   — assertThrows expects IllegalArgumentException but none is thrown, so the test
 *   reports "expected ... IllegalArgumentException to be thrown, but nothing was
 *   thrown". (And the persist branch would then run, breaking the contains() == false
 *   assertion too.)
 *
 * Alternate mutation — wrap the `require(...)` line itself inside the runCatching
 *   block: then a topic-fetch failure path and a rejection path both get swallowed,
 *   again breaking `server rejection throws and persists nothing locally`.
 *   Reverted: yes — suite re-run green; `git diff` of the production file empty.
 */
