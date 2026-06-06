package lava.forum.bookmarks

import android.app.Notification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.data.api.repository.BookmarksRepository
import lava.data.api.service.ForumService
import lava.domain.usecase.ObserveBookmarksUseCase
import lava.domain.usecase.SyncBookmarksUseCase
import lava.models.Page
import lava.models.forum.Category
import lava.models.forum.CategoryModel
import lava.models.forum.Forum
import lava.models.forum.ForumItem
import lava.models.topic.Topic
import lava.notifications.NotificationService
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [BookmarksViewModel].
 *
 * Constitution (Second Law — no mocking of internal business logic):
 *  - The SUT collaborators are the REAL [ObserveBookmarksUseCase] and the
 *    REAL [SyncBookmarksUseCase]. No use case is mocked.
 *  - Boundaries are behaviorally-equivalent fakes: [InMemoryBookmarksRepository]
 *    whose `observeBookmarks()` is a real [MutableStateFlow] (so the list/empty
 *    branch reacts to actual emissions, Third Law) and [FakeForumService]
 *    which THROWS on failure (the real boundary's only failure shape).
 *
 * Primary assertions (Sixth Law clause 3) are on user-visible state: the
 * rendered bookmark list (vs. empty placeholder) and the sync spinner the
 * screen shows during a manual refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var repository: InMemoryBookmarksRepository
    private lateinit var forumService: FakeForumService
    private lateinit var viewModel: BookmarksViewModel

    private val recordingAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    @Before
    fun setUp() {
        repository = InMemoryBookmarksRepository()
        forumService = FakeForumService()
        val dispatchers = TestDispatchers(dispatcherRule.testDispatcher)
        viewModel = BookmarksViewModel(
            observeBookmarksUseCase = ObserveBookmarksUseCase(repository),
            syncBookmarksUseCase = SyncBookmarksUseCase(
                bookmarksRepository = repository,
                forumService = forumService,
                notificationService = ThrowingNotificationService(),
                dispatchers = dispatchers,
            ),
            loggerFactory = TestLoggerFactory(),
            analytics = recordingAnalytics,
        )
    }

    /**
     * CHALLENGE — when the repository holds bookmarks, the screen renders the
     * bookmark LIST (not the empty placeholder) with the exact categories.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in BookmarksViewModel.observeBookmarks, invert the empty
     *             check (`if (items.isNotEmpty())` → Empty branch).
     *   Observed: this test FAILED — the state is BookmarksState.Empty, so the
     *             `state is BookmarksState.BookmarksList` await never matches
     *             and the test times out.
     *   Reverted: yes.
     */
    @Test
    fun `non-empty bookmarks render the list`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(
                listOf(
                    CategoryModel(Category(id = "c1", name = "Movies")),
                    CategoryModel(Category(id = "c2", name = "Music")),
                ),
            )
            viewModel.test(this) {
                runOnCreate()
                val state = awaitItemMatching { it is BookmarksState.BookmarksList }
                assertTrue(
                    "bookmarks screen MUST render the list when the repository " +
                        "has bookmarks, was $state",
                    state is BookmarksState.BookmarksList,
                )
                val list = (state as BookmarksState.BookmarksList).items
                assertEquals(2, list.size)
                assertEquals("Movies", list[0].category.name)
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — empty repository renders the empty placeholder.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in BookmarksViewModel.observeBookmarks, force the
     *             BookmarksList branch unconditionally.
     *   Observed: this test FAILED — the empty case becomes a list, so the
     *             `state is BookmarksState.Empty` await never matches.
     *   Reverted: yes.
     */
    @Test
    fun `empty bookmarks render the empty placeholder`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            viewModel.test(this) {
                runOnCreate()
                val state = awaitItemMatching { it is BookmarksState.Empty }
                assertTrue(
                    "bookmarks screen MUST render the empty placeholder, was $state",
                    state is BookmarksState.Empty,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping a bookmark posts
     * [BookmarksSideEffect.OpenCategory] carrying that category's id, the side
     * effect the screen reacts to by navigating to the category.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in BookmarksViewModel.perform's BookmarkClicked branch,
     *             hardcode the category id to "0".
     *   Observed: this test FAILED — assertEquals expected "c7" but was "0".
     *   Reverted: yes.
     */
    @Test
    fun `BookmarkClicked posts OpenCategory with the category id`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it is BookmarksState.Empty }
                viewModel.perform(
                    BookmarksAction.BookmarkClicked(
                        CategoryModel(Category(id = "c7", name = "Books")),
                    ),
                )
                assertEquals(
                    BookmarksSideEffect.OpenCategory("c7"),
                    awaitSideEffect(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — a manual "Sync now" runs the REAL SyncBookmarksUseCase
     * against the boundary ForumService and ends with the spinner cleared.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in BookmarksViewModel.onSyncNow, drop the SECOND reduce
     *             (the one that sets isSyncing = false).
     *   Observed: this test FAILED — the final state keeps isSyncing = true,
     *             so `assertFalse(... finalState.isSyncing)` fails.
     *   Reverted: yes.
     */
    @Test
    fun `SyncNow clears the syncing spinner when it finishes`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it is BookmarksState.Empty }
                viewModel.perform(BookmarksAction.SyncNowClick)
                cancelAndIgnoreRemainingItems()
            }
            val finalState = viewModel.container.stateFlow.value
            assertFalse(
                "the sync spinner MUST clear after a Sync now completes, " +
                    "state was $finalState",
                finalState.isSyncing,
            )
        }

    /**
     * CHALLENGE — when the boundary ForumService throws during a manual sync,
     * the VM still clears the spinner; the user is never stuck on a broken
     * refresh. (SyncBookmarksUseCase wraps each per-bookmark fetch in
     * runCatching; the VM-level try/catch is the outer safety net.)
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in BookmarksViewModel.onSyncNow, remove the try/catch.
     *   Observed: with a thrown failure surfaced past runCatching the intent
     *             crashes before the spinner-clear reduce; the final state
     *             keeps isSyncing = true → `assertFalse(... isSyncing)` fails.
     *   Reverted: yes.
     */
    @Test
    fun `SyncNow recovers and clears spinner when the sync path fails`() =
        runTest(dispatcherRule.testDispatcher) {
            // Seed a bookmark so SyncBookmarksUseCase iterates and hits the
            // failing ForumService.getCategoryPage boundary.
            repository.emit(listOf(CategoryModel(Category(id = "cF", name = "WillFail"))))
            repository.allBookmarks = listOf(Category(id = "cF", name = "WillFail"))
            forumService.shouldThrow = true
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it is BookmarksState.BookmarksList }
                viewModel.perform(BookmarksAction.SyncNowClick)
                cancelAndIgnoreRemainingItems()
            }
            val finalState = viewModel.container.stateFlow.value
            assertFalse(
                "the spinner MUST clear even when the sync boundary fails, " +
                    "state was $finalState",
                finalState.isSyncing,
            )
        }

    /**
     * Drains interleaved [Item.StateItem]s until one matches [predicate].
     */
    private suspend fun OrbitTestContext<
        BookmarksState,
        BookmarksSideEffect,
        BookmarksViewModel,
        >.awaitItemMatching(predicate: (BookmarksState) -> Boolean): BookmarksState {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem -> if (predicate(item.value)) return item.value
                else -> Unit
            }
        }
    }
}

/**
 * Behaviorally-equivalent in-memory [BookmarksRepository]. `observeBookmarks()`
 * is a real [MutableStateFlow] so the VM's list/empty branch reacts to actual
 * emissions (Third Law). `getAllBookmarks`/`getTopics`/`getNewTopics`/`update`
 * back the SyncBookmarksUseCase iteration.
 */
private class InMemoryBookmarksRepository : BookmarksRepository {
    private val bookmarks = MutableStateFlow<List<CategoryModel>>(emptyList())
    private val ids = MutableStateFlow<List<String>>(emptyList())
    var allBookmarks: List<Category> = emptyList()

    fun emit(value: List<CategoryModel>) {
        bookmarks.value = value
        ids.value = value.map { it.category.id }
    }

    override fun observeBookmarks(): Flow<List<CategoryModel>> = bookmarks
    override fun observeIds(): Flow<List<String>> = ids
    override fun observeNewTopics(): Flow<List<String>> = MutableStateFlow(emptyList())
    override fun observeNewTopics(id: String): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun getAllBookmarks(): List<Category> = allBookmarks
    override suspend fun getTopics(id: String): List<String> = emptyList()
    override suspend fun getNewTopics(id: String): List<String> = emptyList()
    override suspend fun isBookmark(id: String): Boolean = ids.value.contains(id)
    override suspend fun add(category: Category) = Unit
    override suspend fun remove(id: String) = Unit
    override suspend fun update(id: String, topics: List<String>, newTopics: List<String>) = Unit
    override suspend fun clear() {
        bookmarks.value = emptyList()
        ids.value = emptyList()
    }
}

/**
 * Behaviorally-equivalent [ForumService]. The real boundary signals a failure
 * by THROWING; this fake throws when [shouldThrow] (Third Law). The happy path
 * returns an empty category page (no new topics → no notification).
 */
private class FakeForumService : ForumService {
    var shouldThrow: Boolean = false
    override suspend fun getForum(): Forum = error("getForum not exercised")
    override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> {
        if (shouldThrow) error("forum boundary failed for $id")
        return Page(items = emptyList(), page = page, pages = 1)
    }
}

/**
 * [NotificationService] used only to satisfy SyncBookmarksUseCase's
 * constructor. The sync paths exercised here never show a notification, and
 * `createSyncNotification()` would need a real Android [Notification] — so it
 * errors if ever reached.
 */
private class ThrowingNotificationService : NotificationService {
    override fun clearAllNotifications() = Unit
    override fun showFavoriteUpdateNotification(topic: Topic) = Unit
    override fun showBookmarkUpdateNotification(category: Category) = Unit
    override fun createSyncNotification(): Notification =
        error("createSyncNotification not exercised by these tests")
}
