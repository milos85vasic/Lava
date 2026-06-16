package lava.domain.usecase

import kotlinx.coroutines.test.runTest
import lava.data.api.repository.ForumRepository
import lava.data.api.service.ForumService
import lava.models.Page
import lava.models.forum.Category
import lava.models.forum.Forum
import lava.models.forum.ForumCategory
import lava.models.forum.ForumItem
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real-stack test for [GetCategoryUseCase] wired to the REAL
 * [EnsureForumLoadUseCase] and REAL [RefreshForumUseCase] implementations
 * (NOT mocks). Only the outermost boundaries are faked: an in-memory
 * [ForumRepository] that behaves like the real Room-backed store (storeForum
 * populates, getCategory resolves by id or returns null, freshness reflects
 * stored state) and a fake [ForumService] at the network boundary that yields
 * a canned [Forum].
 *
 * The user-visible feature this covers: opening a forum category. When the
 * requested category isn't present in the local store yet, the use case MUST
 * refresh from the service so the Category screen can actually render the
 * category — both the cold-start "nothing loaded" path (EnsureForumLoad
 * refreshes) and the stale "loaded but missing this id" path
 * (the null branch re-refreshes).
 *
 * Bluff-Audit (§6.J clause 2 falsifiability), see FALSIFIABILITY REHEARSAL
 * note at the bottom of this file.
 */
class GetCategoryUseCaseTest {

    /**
     * In-memory ForumRepository fake — behaviorally equivalent to the real
     * store: storeForum replaces the catalogue; getCategory resolves any
     * (recursively nested) ForumCategory by id, else null; isNotEmpty /
     * isForumFresh reflect whether a forum has been stored.
     */
    private class InMemoryForumRepository : ForumRepository {
        private var stored: Forum? = null
        var storeCalls = 0
            private set

        override suspend fun isNotEmpty(): Boolean = stored?.children?.isNotEmpty() == true
        override suspend fun isForumFresh(maxAgeInDays: Int): Boolean = stored != null
        override suspend fun storeForum(forum: Forum) {
            stored = forum
            storeCalls++
        }
        override suspend fun getForum(): Forum = requireNotNull(stored)
        override suspend fun getCategory(id: String): Category? {
            val forum = stored ?: return null
            val match = findCategory(forum.children, id) ?: return null
            return Category(id = match.id, name = match.name)
        }

        private fun findCategory(nodes: List<ForumCategory>, id: String): ForumCategory? {
            for (node in nodes) {
                if (node.id == id) return node
                findCategory(node.children, id)?.let { return it }
            }
            return null
        }
    }

    /**
     * Fake ForumService at the network boundary. Returns a forum whose
     * children depend on how many times it has been asked — lets a test model
     * "first fetch lacks the category, the refresh fetch supplies it".
     */
    private class FakeForumService(private val forums: List<Forum>) : ForumService {
        var getForumCalls = 0
            private set

        override suspend fun getForum(): Forum {
            val index = getForumCalls.coerceAtMost(forums.lastIndex)
            getForumCalls++
            return forums[index]
        }

        override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> =
            throw UnsupportedOperationException("not used by GetCategoryUseCase")
    }

    private fun forumOf(vararg categories: ForumCategory) = Forum(children = categories.toList())

    private fun build(
        repository: ForumRepository,
        service: ForumService,
        scope: kotlinx.coroutines.test.TestScope,
    ): GetCategoryUseCase {
        val dispatchers = scope.testDispatchers()
        val refresh = RefreshForumUseCase(repository, service, dispatchers)
        val ensure = EnsureForumLoadUseCase(refresh, repository, dispatchers)
        return GetCategoryUseCase(
            ensureForumLoadUseCase = ensure,
            refreshForumUseCase = refresh,
            forumRepository = repository,
            dispatchers = dispatchers,
        )
    }

    @Test
    fun `cold start loads the forum and returns the requested category`() = runTest {
        val repository = InMemoryForumRepository()
        // Nothing stored locally; the service is the only source of the forum.
        val service = FakeForumService(
            listOf(forumOf(ForumCategory(id = "10", name = "Movies"))),
        )
        val useCase = build(repository, service, this)

        val category = useCase("10")

        // Primary user-visible state: the resolved category the screen renders.
        assertEquals("10", category.id)
        assertEquals("Movies", category.name)
        // The empty local store forced exactly one network refresh.
        assertEquals(1, service.getForumCalls)
    }

    @Test
    fun `missing category triggers a refresh that supplies it`() = runTest {
        val repository = InMemoryForumRepository()
        // First fetch returns a forum WITHOUT id "20"; the second fetch adds it.
        val service = FakeForumService(
            listOf(
                forumOf(ForumCategory(id = "10", name = "Movies")),
                forumOf(
                    ForumCategory(id = "10", name = "Movies"),
                    ForumCategory(id = "20", name = "Music"),
                ),
            ),
        )
        val useCase = build(repository, service, this)

        val category = useCase("20")

        // Primary user-visible state: the previously-absent category now resolves.
        assertEquals("20", category.id)
        assertEquals("Music", category.name)
        // Two service fetches: EnsureForumLoad's initial refresh (no "20"),
        // then the null-branch refresh that supplies "20".
        assertEquals(2, service.getForumCalls)
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation — GetCategoryUseCase.invoke: delete the
 *   `if (category == null) { refreshForumUseCase.invoke() }` block (so a
 *   missing category is never refilled before the final requireNotNull).
 *   Observed failure: `missing category triggers a refresh that supplies it`
 *   FAILED — requireNotNull throws IllegalArgumentException because "20" is
 *   absent after the first (incomplete) fetch and no second refresh happens.
 *   Reverted: yes — suite re-run green.
 */
