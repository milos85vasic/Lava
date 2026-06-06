package lava.search.result.categories

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
import lava.search.result.domain.GetCategoriesByGroupIdUseCase
import lava.search.result.domain.GetFlattenForumTreeUseCase
import lava.search.result.domain.models.ForumTreeItem
import lava.search.result.domain.models.SelectState
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Real-stack Orbit ViewModel test for [CategorySelectionViewModel].
 *
 * The SUT is the REAL [CategorySelectionViewModel] wired to the REAL
 * [GetFlattenForumTreeUseCase] and [GetCategoriesByGroupIdUseCase], each
 * of which delegates to the REAL [GetForumUseCase] → [EnsureForumLoadUseCase]
 * → [RefreshForumUseCase] chain. Only the [ForumRepository] persistence
 * seam and the [ForumService] network seam are replaced with behaviorally-
 * equivalent in-memory fakes. No UseCase is mocked (§6.J, Second Law).
 *
 * The forum tree used here is a real 3-level shape (Root → Group →
 * Category) so the production flattening, expand, and group-select logic
 * is genuinely exercised — not a single-node stub that would hide the
 * traversal branches.
 *
 * ## Test classification
 * VM-CONTRACT — primary assertions on the rendered
 * [CategorySelectionState] (the flattened tree the screen renders, with
 * per-item select state) and on the [CategorySelectionSideEffect] payloads
 * the dialog consumes. The rendered-UI Challenge is owed per
 * `feature/CLAUDE.md`.
 *
 * ## Bluff-Audit
 * See commit body for the per-class mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategorySelectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeForumRepository(private val forum: Forum) : ForumRepository {
        var stored: Forum? = forum
        override suspend fun isNotEmpty(): Boolean = stored != null
        override suspend fun isForumFresh(maxAgeInDays: Int): Boolean = true
        override suspend fun storeForum(forum: Forum) { stored = forum }
        override suspend fun getForum(): Forum = stored ?: throw IllegalStateException("not loaded")
        override suspend fun getCategory(id: String): Category? = null
    }

    private class FailingForumRepository : ForumRepository {
        override suspend fun isNotEmpty(): Boolean = true
        override suspend fun isForumFresh(maxAgeInDays: Int): Boolean = true
        override suspend fun storeForum(forum: Forum) {}
        override suspend fun getForum(): Forum = throw IllegalStateException("forum store unavailable")
        override suspend fun getCategory(id: String): Category? = null
    }

    private class FakeForumService(private val forum: Forum) : ForumService {
        override suspend fun getForum(): Forum = forum
        override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> =
            throw UnsupportedOperationException("not used")
    }

    /**
     * Root "r" → Group "g1" (children: Category "c1", "c2") + Group "g2"
     * (children: Category "c3"). A realistic forum-tree shape.
     */
    private val forum = Forum(
        children = listOf(
            ForumCategory(
                id = "r",
                name = "Root",
                children = listOf(
                    ForumCategory("g1", "Group One", listOf(ForumCategory("c1", "Cat 1"), ForumCategory("c2", "Cat 2"))),
                    ForumCategory("g2", "Group Two", listOf(ForumCategory("c3", "Cat 3"))),
                ),
            ),
        ),
    )

    private fun createViewModel(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        repository: ForumRepository = FakeForumRepository(forum),
    ): CategorySelectionViewModel {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(scheduler))
        val refresh = RefreshForumUseCase(repository, FakeForumService(forum), dispatchers)
        val ensure = EnsureForumLoadUseCase(refresh, repository, dispatchers)
        val getForum = GetForumUseCase(ensure, repository, dispatchers)
        return CategorySelectionViewModel(
            getFlattenForumTreeUseCase = GetFlattenForumTreeUseCase(getForum, dispatchers),
            getCategoriesByGroupIdUseCase = GetCategoriesByGroupIdUseCase(getForum, dispatchers),
            loggerFactory = TestLoggerFactory(),
        )
    }

    private fun successItems(state: CategorySelectionState): List<ForumTreeItem> {
        assertTrue("expected Success, got $state", state is CategorySelectionState.Success)
        return (state as CategorySelectionState.Success).items
    }

    // VM-CONTRACT
    @Test
    fun setSelectedCategories_loads_collapsed_root_only() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                vm.setSelectedCategories(emptyList())
                cancelAndIgnoreRemainingItems()
            }
            val items = successItems(vm.container.stateFlow.value)
            // Collapsed root => only the single Root node is rendered.
            assertEquals(1, items.size)
            assertTrue(items[0] is ForumTreeItem.Root)
            assertEquals("r", items[0].id)
        }

    // VM-CONTRACT
    @Test
    fun ExpandClick_root_reveals_its_groups() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                vm.setSelectedCategories(emptyList())
                val root = successItems(vm.container.stateFlow.value).first()
                vm.perform(CategorySelectionAction.ExpandClick(root))
                cancelAndIgnoreRemainingItems()
            }
            val items = successItems(vm.container.stateFlow.value)
            // Root + its two groups now visible.
            assertEquals(listOf("r", "g1", "g2"), items.map { it.id })
            assertTrue("g1 must render as a Group", items[1] is ForumTreeItem.Group)
        }

    // VM-CONTRACT
    @Test
    fun ExpandClick_group_reveals_its_categories() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                vm.setSelectedCategories(emptyList())
                val root = successItems(vm.container.stateFlow.value).first()
                vm.perform(CategorySelectionAction.ExpandClick(root))
                val group1 = successItems(vm.container.stateFlow.value).first { it.id == "g1" }
                vm.perform(CategorySelectionAction.ExpandClick(group1))
                cancelAndIgnoreRemainingItems()
            }
            val items = successItems(vm.container.stateFlow.value)
            // Root, g1 (expanded) + its categories c1, c2, then g2.
            assertEquals(listOf("r", "g1", "c1", "c2", "g2"), items.map { it.id })
        }

    // VM-CONTRACT
    @Test
    fun SelectClick_on_a_group_selects_it_and_emits_OnSelect_with_group_plus_children() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            var captured: CategorySelectionSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.setSelectedCategories(emptyList())
                val root = successItems(vm.container.stateFlow.value).first()
                vm.perform(CategorySelectionAction.ExpandClick(root))
                val group1 = successItems(vm.container.stateFlow.value).first { it.id == "g1" }
                vm.perform(CategorySelectionAction.SelectClick(group1))
                captured = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            // Side effect carries the group + its two children (GetCategoriesByGroupIdUseCase).
            assertTrue("expected OnSelect, got $captured", captured is CategorySelectionSideEffect.OnSelect)
            val ids = (captured as CategorySelectionSideEffect.OnSelect).items.map { it.id }
            assertEquals(listOf("g1", "c1", "c2"), ids)
            // Rendered state: g1 is now Selected.
            val group = successItems(vm.container.stateFlow.value).first { it.id == "g1" } as ForumTreeItem.Group
            assertEquals(SelectState.Selected, group.selectState)
        }

    // VM-CONTRACT
    @Test
    fun SelectClick_twice_on_a_group_deselects_it_and_emits_OnRemove() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            var lastEffect: CategorySelectionSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.setSelectedCategories(emptyList())
                val root = successItems(vm.container.stateFlow.value).first()
                vm.perform(CategorySelectionAction.ExpandClick(root))
                val group1 = successItems(vm.container.stateFlow.value).first { it.id == "g1" }
                vm.perform(CategorySelectionAction.SelectClick(group1)) // select
                drainSideEffect()
                val selectedGroup = successItems(vm.container.stateFlow.value).first { it.id == "g1" }
                vm.perform(CategorySelectionAction.SelectClick(selectedGroup)) // deselect
                lastEffect = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            assertTrue("expected OnRemove on second tap, got $lastEffect", lastEffect is CategorySelectionSideEffect.OnRemove)
            val group = successItems(vm.container.stateFlow.value).first { it.id == "g1" } as ForumTreeItem.Group
            assertEquals(SelectState.Unselected, group.selectState)
        }

    // VM-CONTRACT
    @Test
    fun preselected_category_renders_its_group_as_PartSelected() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                // Pre-select one child category of g1.
                vm.setSelectedCategories(listOf(Category("c1", "Cat 1")))
                val root = successItems(vm.container.stateFlow.value).first()
                vm.perform(CategorySelectionAction.ExpandClick(root))
                cancelAndIgnoreRemainingItems()
            }
            val group = successItems(vm.container.stateFlow.value).first { it.id == "g1" } as ForumTreeItem.Group
            // A group with some (not all) children selected is PartSelected.
            assertEquals(SelectState.PartSelected, group.selectState)
        }

    // VM-CONTRACT
    @Test
    fun load_failure_renders_Error_state() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler, repository = FailingForumRepository())
            vm.test(this) {
                runOnCreate()
                vm.setSelectedCategories(emptyList())
                cancelAndIgnoreRemainingItems()
            }
            assertTrue(
                "expected Error when the forum store fails, got ${vm.container.stateFlow.value}",
                vm.container.stateFlow.value is CategorySelectionState.Error,
            )
        }
}

/**
 * Drains the item stream until the first [CategorySelectionSideEffect] is
 * observed, ignoring interleaved state items.
 */
private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<CategorySelectionState, CategorySelectionSideEffect, CategorySelectionViewModel>.drainSideEffect(): CategorySelectionSideEffect {
    while (true) {
        val item = awaitItem()
        if (item is org.orbitmvi.orbit.test.Item.SideEffectItem) return item.value
    }
}
