package lava.domain.usecase

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.service.TopicService
import lava.models.Page
import lava.models.topic.BaseTopic
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.testing.TestDispatchers
import lava.testing.repository.TestFavoritesRepository
import lava.testing.repository.TestVisitedRepository
import lava.testing.service.TestBackgroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Anti-bluff use-case-level test for LVA-070 — the providerId thread through the
 * REAL domain use cases ([ToggleFavoriteUseCaseImpl] → [AddLocalFavoriteUseCase],
 * and [GetTopicUseCase] → [VisitTopicUseCase]) wired to the behaviorally-equivalent
 * shared [TestFavoritesRepository] / [TestVisitedRepository] fakes (which now
 * persist the providerId column, mirroring the real Room rows).
 *
 * Constitution:
 * - Second Law: the SUTs are REAL use case implementations, not mocks; only the
 *   TopicService boundary (a tiny canned fake) and the repository fakes are faked.
 * - Sixth Law clause 3: the primary assertions read the PERSISTED provider id
 *   back out of the repository (`providerIdOf`), the value that later routes the
 *   topic download branch to HTTP_DOWNLOAD.
 *
 * Bluff-Audit: ProviderIdThreadingUseCaseTest
 *   Mutation: in ToggleFavoriteUseCaseImpl.invoke, drop the providerId argument
 *             to addLocalFavoriteUseCase (`addLocalFavoriteUseCase(id)`).
 *   Observed-Failure: `toggling favorite on an archiveorg topic persists
 *     providerId=archiveorg` FAILED — expected:<archiveorg> but was:<null>.
 *   Reverted: yes
 *
 * Bluff-Audit: ProviderIdThreadingUseCaseTest (visit path)
 *   Mutation: in GetTopicUseCase.invoke, drop the providerId argument to
 *             visitTopicUseCase (`visitTopicUseCase(it)`).
 *   Observed-Failure: `opening an archiveorg topic persists the visit with
 *     providerId=archiveorg` FAILED — expected:<archiveorg> but was:<null>.
 *   Reverted: yes
 */
class ProviderIdThreadingUseCaseTest {

    private val dispatchers = TestDispatchers(UnconfinedTestDispatcher())

    /** Canned TopicService boundary — getTopic / getTopicPage return the id's page. */
    private class CannedTopicService : TopicService {
        override suspend fun getTopic(id: String): Topic =
            BaseTopic(id = id, title = "Archive item $id", author = null, category = null)

        override suspend fun getTopicPage(id: String, page: Int?, providerId: String?): TopicPage = TopicPage(
            id = id,
            title = "Archive item $id",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = Page(items = emptyList<Post>(), page = 1, pages = 1),
        )

        override suspend fun getCommentsPage(id: String, page: Int): Page<Post> =
            Page(items = emptyList(), page = 1, pages = 1)

        override suspend fun addComment(topicId: String, message: String): Boolean = true
    }

    private fun toggleUseCase(favorites: TestFavoritesRepository): ToggleFavoriteUseCase =
        ToggleFavoriteUseCaseImpl(
            addLocalFavoriteUseCase = AddLocalFavoriteUseCase(CannedTopicService(), favorites, dispatchers),
            removeLocalFavoriteUseCase = RemoveLocalFavoriteUseCase(favorites),
            favoritesRepository = favorites,
            backgroundService = TestBackgroundService(),
            dispatchers = dispatchers,
        )

    @Test
    fun `toggling favorite on an archiveorg topic persists providerId=archiveorg`() = runTest {
        val favorites = TestFavoritesRepository()

        toggleUseCase(favorites)("arch-1", providerId = "archiveorg")

        assertEquals(
            "the favorited archiveorg topic must persist its source provider",
            "archiveorg",
            favorites.providerIdOf("arch-1"),
        )
    }

    @Test
    fun `toggling favorite with no provider persists null`() = runTest {
        val favorites = TestFavoritesRepository()

        toggleUseCase(favorites)("rt-1")

        assertNull(
            "a favorite toggled with no provider must persist null",
            favorites.providerIdOf("rt-1"),
        )
    }

    @Test
    fun `opening an archiveorg topic persists the visit with providerId=archiveorg`() = runTest {
        val favorites = TestFavoritesRepository()
        val visited = TestVisitedRepository()
        val getTopic = GetTopicUseCase(
            topicService = CannedTopicService(),
            visitTopicUseCase = VisitTopicUseCase(visited, favorites, dispatchers),
            dispatchers = dispatchers,
        )

        getTopic("arch-2", providerId = "archiveorg")

        assertEquals(
            "opening an archiveorg topic must persist the visit's source provider",
            "archiveorg",
            visited.providerIdOf("arch-2"),
        )
    }

    @Test
    fun `opening a topic with no provider persists the visit with null`() = runTest {
        val favorites = TestFavoritesRepository()
        val visited = TestVisitedRepository()
        val getTopic = GetTopicUseCase(
            topicService = CannedTopicService(),
            visitTopicUseCase = VisitTopicUseCase(visited, favorites, dispatchers),
            dispatchers = dispatchers,
        )

        getTopic("rt-2")

        assertNull(
            "a visit with no provider must persist null",
            visited.providerIdOf("rt-2"),
        )
    }
}
