package lava.forum

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.ForumRepository
import lava.data.api.service.ForumService
import lava.domain.usecase.EnsureForumLoadUseCase
import lava.domain.usecase.GetForumUseCase
import lava.domain.usecase.RefreshForumUseCase
import lava.models.Page
import lava.models.forum.Category
import lava.models.forum.Forum
import lava.models.forum.ForumCategory
import lava.models.forum.ForumItem
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Real-stack Orbit ViewModel test for [ForumViewModel].
 *
 * The SUT is the REAL [ForumViewModel] wired to the REAL [GetForumUseCase]
 * → REAL [EnsureForumLoadUseCase] → REAL [RefreshForumUseCase] chain. Only
 * the outermost boundaries — the [ForumRepository] persistence seam and
 * the [ForumService] network seam — are replaced with behaviorally-
 * equivalent in-memory fakes. The fakes enforce the real freshness +
 * empty-store contract: when the store is empty OR stale,
 * [EnsureForumLoadUseCase] triggers [RefreshForumUseCase] which pulls from
 * the service and stores the result — exactly the production path. No
 * UseCase is mocked (§6.J, Second Law).
 *
 * ## Test classification
 * VM-CONTRACT — primary assertions on the rendered [ForumState] the Forum
 * screen reads, plus side-effect emission. The rendered-UI Challenge is
 * owed per `feature/CLAUDE.md`.
 *
 * ## Bluff-Audit
 * See commit body for the per-class mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Behaviorally-equivalent in-memory [ForumRepository]. Mirrors the real
     * freshness contract: an empty/stale store causes
     * [EnsureForumLoadUseCase] to refresh from the service.
     */
    private class FakeForumRepository : ForumRepository {
        var stored: Forum? = null
        var fresh: Boolean = false
        var getForumError: Throwable? = null
        override suspend fun isNotEmpty(): Boolean = stored != null
        override suspend fun isForumFresh(maxAgeInDays: Int): Boolean = fresh
        override suspend fun storeForum(forum: Forum) {
            stored = forum
            fresh = true
        }
        override suspend fun getForum(): Forum {
            getForumError?.let { throw it }
            return stored ?: throw IllegalStateException("forum not loaded")
        }
        override suspend fun getCategory(id: String): Category? = null
    }

    /** Behaviorally-equivalent in-memory [ForumService]; [error] is mutable so a test can flip it between attempts. */
    private class FakeForumService(
        private val forum: Forum,
        var error: Throwable? = null,
    ) : ForumService {
        var getForumCalls = 0
            private set
        override suspend fun getForum(): Forum {
            getForumCalls += 1
            error?.let { throw it }
            return forum
        }
        override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> =
            throw UnsupportedOperationException("not used")
    }

    private fun createViewModel(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        repository: FakeForumRepository,
        service: FakeForumService,
    ): ForumViewModel {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(scheduler))
        val refresh = RefreshForumUseCase(repository, service, dispatchers)
        val ensure = EnsureForumLoadUseCase(refresh, repository, dispatchers)
        val getForum = GetForumUseCase(ensure, repository, dispatchers)
        return ForumViewModel(getForum, TestLoggerFactory())
    }

    private val sampleForum = Forum(
        children = listOf(
            ForumCategory(id = "1", name = "Movies"),
            ForumCategory(id = "2", name = "Music"),
        ),
    )

    // VM-CONTRACT
    @Test
    fun onCreate_loads_forum_from_service_and_renders_Loaded() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeForumRepository() // empty -> triggers refresh
            val service = FakeForumService(sampleForum)
            val vm = createViewModel(testScheduler, repo, service)
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value
            assertTrue("expected Loaded, got $state", state is ForumState.Loaded)
            state as ForumState.Loaded
            assertEquals(listOf("Movies", "Music"), state.forum.map { it.item.name })
            // every category starts collapsed.
            assertTrue("all collapsed initially", state.forum.none { it.expanded })
            // Real-stack proof: the empty store forced a real service fetch + store.
            assertEquals(1, service.getForumCalls)
            assertEquals(sampleForum, repo.stored)
        }

    // VM-CONTRACT
    @Test
    fun onCreate_service_failure_renders_Error_state() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeForumRepository() // empty -> refresh attempted
            val boom = IllegalStateException("network down")
            val service = FakeForumService(sampleForum, error = boom)
            val vm = createViewModel(testScheduler, repo, service)
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value
            assertTrue("expected Error, got $state", state is ForumState.Error)
            // The thrown throwable's message must survive to the rendered Error state
            // (coroutineScope may re-wrap the instance, so assert on the message).
            assertEquals("network down", (state as ForumState.Error).error.message)
        }

    // VM-CONTRACT
    @Test
    fun RetryClick_recovers_from_Error_to_Loaded() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeForumRepository()
            // Service fails on the first attempt, then heals before the retry.
            val service = FakeForumService(sampleForum, error = IllegalStateException("flaky"))
            val vm = createViewModel(testScheduler, repo, service)
            vm.test(this) {
                runOnCreate()
                assertTrue(
                    "expected Error after the failing initial load, got ${vm.container.stateFlow.value}",
                    vm.container.stateFlow.value is ForumState.Error,
                )
                // The transient failure clears; RetryClick must drive a successful reload.
                service.error = null
                vm.perform(ForumAction.RetryClick)
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value
            assertTrue("expected Loaded after retry, got $state", state is ForumState.Loaded)
            assertEquals(listOf("Movies", "Music"), (state as ForumState.Loaded).forum.map { it.item.name })
        }

    // VM-CONTRACT
    @Test
    fun ExpandClick_expands_only_the_clicked_category() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeForumRepository()
            val service = FakeForumService(sampleForum)
            val vm = createViewModel(testScheduler, repo, service)
            vm.test(this) {
                runOnCreate()
                val loaded = vm.container.stateFlow.value as ForumState.Loaded
                vm.perform(ForumAction.ExpandClick(loaded.forum.first()))
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value as ForumState.Loaded
            assertTrue("first category must be expanded", state.forum[0].expanded)
            assertTrue("second category must stay collapsed", !state.forum[1].expanded)
        }

    // VM-CONTRACT
    @Test
    fun ExpandClick_collapses_a_category_already_expanded() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeForumRepository()
            val service = FakeForumService(sampleForum)
            val vm = createViewModel(testScheduler, repo, service)
            vm.test(this) {
                runOnCreate()
                val loaded = vm.container.stateFlow.value as ForumState.Loaded
                // First expand, then expand the SAME item again -> collapse.
                vm.perform(ForumAction.ExpandClick(loaded.forum.first()))
                val expanded = vm.container.stateFlow.value as ForumState.Loaded
                vm.perform(ForumAction.ExpandClick(expanded.forum.first()))
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value as ForumState.Loaded
            assertTrue("clicking an expanded item again collapses it", !state.forum[0].expanded)
        }

    // VM-CONTRACT
    @Test
    fun CategoryClick_emits_OpenCategory_with_category_id() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeForumRepository()
            val service = FakeForumService(sampleForum)
            val vm = createViewModel(testScheduler, repo, service)
            var captured: ForumSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(ForumAction.CategoryClick(lava.models.forum.ForumCategory(id = "42", name = "Books")))
                captured = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(ForumSideEffect.OpenCategory("42"), captured)
        }
}

/**
 * Drains the item stream until the first [ForumSideEffect] is observed,
 * ignoring interleaved state items (same pattern as
 * [lava.search.result.SearchResultViewModelFallbackTest]).
 */
private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<ForumState, ForumSideEffect, ForumViewModel>.drainSideEffect(): ForumSideEffect {
    while (true) {
        val item = awaitItem()
        if (item is org.orbitmvi.orbit.test.Item.SideEffectItem) return item.value
    }
}
