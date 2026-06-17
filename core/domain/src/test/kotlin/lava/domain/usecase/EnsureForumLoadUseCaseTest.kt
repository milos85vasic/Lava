package lava.domain.usecase

import kotlinx.coroutines.test.TestScope
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
 * Real-stack test for [EnsureForumLoadUseCase] wired to the REAL
 * [RefreshForumUseCase] (NOT a mock). Only the outermost boundaries are faked: a
 * controllable in-memory [ForumRepository] whose `isNotEmpty` and `isForumFresh`
 * are settable INDEPENDENTLY (the real Room-backed store derives freshness from a
 * stored timestamp + maxAge, which is orthogonal to whether any rows exist), and
 * a fake [ForumService] at the network boundary.
 *
 * User-visible feature covered: the forum catalogue that backs every Forum /
 * Category screen. EnsureForumLoad's guard — `if (!isNotEmpty() || !isForumFresh())
 * refresh()` — must (a) load an empty store, (b) refresh a STALE store so the user
 * doesn't see outdated categories, and (c) NOT hit the network when the store is
 * already non-empty AND fresh (avoiding a redundant refresh on every screen open).
 *
 * Primary assertions are on the PERSISTED forum content (what the screen renders),
 * never on call counts. Bluff-Audit: see FALSIFIABILITY REHEARSAL at the bottom.
 */
class EnsureForumLoadUseCaseTest {

    /**
     * In-memory ForumRepository with independently-controllable emptiness +
     * freshness. `storeForum` replaces the held forum and marks it fresh — so a
     * refresh performed by the real RefreshForumUseCase is OBSERVABLE as changed
     * stored content (the user-visible signal), not a call count.
     */
    private class ControllableForumRepository(
        initial: Forum?,
        private var fresh: Boolean,
    ) : ForumRepository {
        private var stored: Forum? = initial

        override suspend fun isNotEmpty(): Boolean = stored?.children?.isNotEmpty() == true
        override suspend fun isForumFresh(maxAgeInDays: Int): Boolean = stored != null && fresh
        override suspend fun storeForum(forum: Forum) {
            stored = forum
            fresh = true
        }
        override suspend fun getForum(): Forum = requireNotNull(stored)
        override suspend fun getCategory(id: String): Category? {
            val forum = stored ?: return null
            return forum.children.firstOrNull { it.id == id }?.let { Category(id = it.id, name = it.name) }
        }
    }

    private class FakeForumService(private val forum: Forum) : ForumService {
        var getForumCalls = 0
            private set

        override suspend fun getForum(): Forum {
            getForumCalls++
            return forum
        }

        override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> =
            throw UnsupportedOperationException("not used by EnsureForumLoadUseCase")
    }

    private fun forumOf(vararg categories: ForumCategory) = Forum(children = categories.toList())

    private fun build(
        repository: ForumRepository,
        service: ForumService,
        scope: TestScope,
    ): EnsureForumLoadUseCase {
        val dispatchers = scope.testDispatchers()
        return EnsureForumLoadUseCase(
            refreshForumUseCase = RefreshForumUseCase(repository, service, dispatchers),
            forumRepository = repository,
            dispatchers = dispatchers,
        )
    }

    @Test
    fun `empty store is loaded from the service`() = runTest {
        val repository = ControllableForumRepository(initial = null, fresh = false)
        val service = FakeForumService(forumOf(ForumCategory(id = "10", name = "Movies")))

        build(repository, service, this).invoke()

        // User-visible: the forum is now present with the service's content.
        assertEquals(listOf("10"), repository.getForum().children.map { it.id })
    }

    @Test
    fun `stale non-empty store is refreshed`() = runTest {
        val stale = forumOf(ForumCategory(id = "1", name = "Old"))
        val repository = ControllableForumRepository(initial = stale, fresh = false)
        val freshForum = forumOf(ForumCategory(id = "2", name = "New"))
        val service = FakeForumService(freshForum)

        build(repository, service, this).invoke()

        // User-visible: a stale catalogue was refreshed to the service's content.
        assertEquals(listOf("2"), repository.getForum().children.map { it.id })
    }

    @Test
    fun `fresh non-empty store is NOT refreshed`() = runTest {
        val cached = forumOf(ForumCategory(id = "1", name = "Cached"))
        val repository = ControllableForumRepository(initial = cached, fresh = true)
        val service = FakeForumService(forumOf(ForumCategory(id = "99", name = "ShouldNotAppear")))

        build(repository, service, this).invoke()

        // User-visible: the fresh cached catalogue is retained (no redundant refresh).
        assertEquals(listOf("1"), repository.getForum().children.map { it.id })
        // Secondary: the network service was never consulted.
        assertEquals(0, service.getForumCalls)
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation — EnsureForumLoadUseCase.invoke: change the guard
 *   `if (!forumRepository.isNotEmpty() || !forumRepository.isForumFresh())`
 *   to `if (true)` (always refresh, even when the store is non-empty + fresh).
 *   Observed failure: `fresh non-empty store is NOT refreshed` FAILS —
 *   expected:<[1]> but was:<[99]> (the cached "1" was clobbered by the service's
 *   "99"), and the secondary getForumCalls assertion would also fire (0 != 1).
 *   Reverted: yes — suite re-run green.
 */
