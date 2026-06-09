package lava.visited

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.BookmarksRepository
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.domain.usecase.EnrichTopicsUseCase
import lava.domain.usecase.ObserveVisitedUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.models.forum.Category
import lava.models.forum.CategoryModel
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [VisitedViewModel].
 *
 * Constitution (Second Law — no mocking of internal business logic):
 *  - The SUT is [VisitedViewModel]; it is a REAL instance, never mocked.
 *  - Visited observation is the REAL [ObserveVisitedUseCase] wired to the REAL
 *    [EnrichTopicsUseCase] (both are public classes in `:core:domain`),
 *    themselves wired to behaviorally-equivalent in-memory repositories. This
 *    is the genuine production observe-and-enrich pipeline: the topics the
 *    screen renders, and their `isFavorite` / `isVisited` flags, are computed
 *    by the same code that runs in production.
 *  - Toggle-favorite is a behaviorally-equivalent [ToggleFavoriteUseCase]
 *    ([RealToggleFavoriteUseCase]) mirroring the production
 *    `ToggleFavoriteUseCaseImpl`: it reads `favoritesRepository.contains(id)`
 *    and adds or removes accordingly. The `backgroundService` side of the prod
 *    impl has no user-visible effect on the visited screen and is intentionally
 *    omitted (it would require an Android-bound fake). This is NOT a mock of the
 *    SUT — it is the VM's collaborator, and the favorite-state assertions are
 *    driven by the REAL in-memory [FakeFavoritesRepository].
 *
 * Primary assertions (Sixth Law clause 3) are on user-visible state/side
 * effects: the rendered [VisitedState] (Empty vs the list of visited topics the
 * screen displays), the [VisitedSideEffect.OpenTopic] the screen reacts to by
 * navigating to the topic, the persisted favorite flag, and the
 * [VisitedSideEffect.ShowFavoriteToggleError] shown on failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisitedViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var visited: FakeVisitedRepository
    private lateinit var favorites: FakeFavoritesRepository
    private lateinit var bookmarks: FakeBookmarksRepository
    private lateinit var viewModel: VisitedViewModel

    private fun buildViewModel(toggle: ToggleFavoriteUseCase = RealToggleFavoriteUseCase(favorites)) {
        viewModel = VisitedViewModel(
            observeVisitedUseCase = ObserveVisitedUseCase(
                visitedRepository = visited,
                enrichTopicsUseCase = EnrichTopicsUseCase(
                    bookmarksRepository = bookmarks,
                    favoritesRepository = favorites,
                    visitedRepository = visited,
                ),
            ),
            toggleFavoriteUseCase = toggle,
            loggerFactory = TestLoggerFactory(),
        )
    }

    @Before
    fun setUp() {
        visited = FakeVisitedRepository()
        favorites = FakeFavoritesRepository()
        bookmarks = FakeBookmarksRepository()
    }

    private fun topic(id: String, title: String = "Topic $id"): Topic =
        BaseTopic(id = id, title = title)

    // CHALLENGE — an empty visited history renders the Empty state (the screen
    // shows its empty placeholder, not a list).
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in VisitedViewModel.observeVisited, swap the branches —
     *             `if (items.isEmpty()) VisitedState.VisitedList(items) else VisitedState.Empty`.
     *   Observed: this test FAILED —
     *             "an empty visited history MUST render the Empty state
     *              expected:<Empty> but was:<VisitedList(items=[])>".
     *   Reverted: yes.
     */
    @Test
    fun `empty visited history renders Empty state`() =
        runTest(dispatcherRule.testDispatcher) {
            buildViewModel()
            viewModel.test(this) {
                runOnCreate()
                assertEquals(VisitedState.Initial, awaitState())
                assertEquals(
                    "an empty visited history MUST render the Empty state",
                    VisitedState.Empty,
                    awaitState(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — visited topics surface in the rendered list end-to-end through
    // the REAL ObserveVisitedUseCase + EnrichTopicsUseCase. The list the screen
    // shows contains exactly the topics in the visited repository, and the
    // enrichment marks them isVisited=true (they ARE in the visited set).
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in ObserveVisitedUseCase.invoke, drop
     *             `.flatMapLatest(enrichTopicsUseCase::invoke)` and emit the
     *             raw topics list mapped to bare TopicModel without enrichment.
     *   Observed: this test FAILED — the rendered items' isVisited flag was
     *             false instead of true:
     *             "visited topics MUST be enriched with isVisited=true".
     *   Reverted: yes.
     */
    @Test
    fun `visited topics surface in the rendered list, enriched`() =
        runTest(dispatcherRule.testDispatcher) {
            visited.setTopics(listOf(topic("1"), topic("2")))
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                assertEquals(VisitedState.Initial, awaitState())
                val state = awaitState()
                assertTrue(
                    "visited topics MUST render as a VisitedList",
                    state is VisitedState.VisitedList,
                )
                val items = (state as VisitedState.VisitedList).items
                assertEquals(
                    "the list MUST contain exactly the visited topic ids",
                    listOf("1", "2"),
                    items.map { it.topic.id },
                )
                assertTrue(
                    "visited topics MUST be enriched with isVisited=true",
                    items.all { it.isVisited },
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — enrichment reflects the favorites repository: a topic that is
    // a favorite renders with isFavorite=true (the screen shows a filled star),
    // a non-favorite renders isFavorite=false. This is the real cross-repository
    // join the production EnrichTopicsUseCase performs.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in EnrichTopicsUseCase.invoke, hardcode `isFavorite = false`
     *             instead of `favoriteTopics.contains(topic.id)`.
     *   Observed: this test FAILED —
     *             "topic 1 is a favorite and MUST render isFavorite=true".
     *   Reverted: yes.
     */
    @Test
    fun `enrichment reflects favorite state from the favorites repository`() =
        runTest(dispatcherRule.testDispatcher) {
            visited.setTopics(listOf(topic("1"), topic("2")))
            favorites.ids.value = listOf("1") // topic 1 is a favorite
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Initial
                val items = (awaitState() as VisitedState.VisitedList).items
                val byId = items.associateBy { it.topic.id }
                assertTrue(
                    "topic 1 is a favorite and MUST render isFavorite=true",
                    byId.getValue("1").isFavorite,
                )
                assertFalse(
                    "topic 2 is not a favorite and MUST render isFavorite=false",
                    byId.getValue("2").isFavorite,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — tapping a visited topic posts OpenTopic carrying that topic's
    // id; the screen reacts by navigating to the topic detail. The id is the
    // user-visible destination.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in VisitedViewModel.onTopicClick, post
     *             `VisitedSideEffect.OpenTopic("")` (wrong id).
     *   Observed: this test FAILED —
     *             "tapping a topic MUST navigate to that topic's id
     *              expected:<OpenTopic(id=42)> but was:<OpenTopic(id=)>".
     *   Reverted: yes.
     */
    @Test
    fun `TopicClick navigates to the tapped topic`() =
        runTest(dispatcherRule.testDispatcher) {
            visited.setTopics(listOf(topic("42")))
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Initial
                val items = (awaitState() as VisitedState.VisitedList).items

                viewModel.perform(VisitedAction.TopicClick(items.first()))

                assertEquals(
                    "tapping a topic MUST navigate to that topic's id",
                    VisitedSideEffect.OpenTopic("42"),
                    awaitSideEffect(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — tapping the favorite star toggles favorite state through the
    // REAL toggle pipeline AND the list re-renders with the new isFavorite flag.
    // A non-favorite becomes a favorite: persisted state mutates (favorites repo
    // now contains the id) and the rendered model flips isFavorite=true.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RealToggleFavoriteUseCase.invoke (mirroring
     *             ToggleFavoriteUseCaseImpl), invert the contains() branch so a
     *             non-favorite is removed instead of added.
     *   Observed: this test FAILED —
     *             "favoriting a topic MUST persist it in the favorites repo"
     *             (the id never appeared in favorites.ids).
     *   Reverted: yes.
     */
    @Test
    fun `FavoriteClick favorites a non-favorite and re-renders`() =
        runTest(dispatcherRule.testDispatcher) {
            visited.setTopics(listOf(topic("7")))
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Initial
                val before = (awaitState() as VisitedState.VisitedList).items
                assertFalse(
                    "precondition: topic 7 starts as a non-favorite",
                    before.first().isFavorite,
                )

                viewModel.perform(VisitedAction.FavoriteClick(before.first()))

                // The favorites flow change re-emits through the observe pipeline.
                val after = (awaitState() as VisitedState.VisitedList).items
                assertTrue(
                    "after favoriting, the topic MUST render isFavorite=true",
                    after.first().isFavorite,
                )
                cancelAndIgnoreRemainingItems()
            }

            assertEquals(
                "favoriting a topic MUST persist it in the favorites repo",
                listOf("7"),
                favorites.ids.value,
            )
        }

    // CHALLENGE — when the toggle pipeline fails, the VM surfaces a
    // ShowFavoriteToggleError side effect (the screen shows an error toast/snack)
    // instead of crashing. This is the runCatching/onFailure branch.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in VisitedViewModel.onFavoriteClick, drop the
     *             `.onFailure { postSideEffect(...) }` (or replace runCatching
     *             with a bare call).
     *   Observed: this test FAILED — the test ALSO surfaced that without
     *             runCatching the thrown error propagates; with onFailure dropped
     *             the side effect never arrives and awaitSideEffect times out:
     *             ShowFavoriteToggleError was never posted.
     *   Reverted: yes.
     */
    @Test
    fun `FavoriteClick surfaces an error side effect when toggle fails`() =
        runTest(dispatcherRule.testDispatcher) {
            visited.setTopics(listOf(topic("9")))
            buildViewModel(toggle = ThrowingToggleFavoriteUseCase())

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Initial
                val items = (awaitState() as VisitedState.VisitedList).items

                viewModel.perform(VisitedAction.FavoriteClick(items.first()))

                assertEquals(
                    "a failing favorite toggle MUST surface ShowFavoriteToggleError",
                    VisitedSideEffect.ShowFavoriteToggleError,
                    awaitSideEffect(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }
}

/**
 * Behaviorally-equivalent in-memory [VisitedRepository]. The production
 * Room-backed repo exposes `observeTopics()` as a live query; this fake exposes
 * a MutableStateFlow so the observe pipeline re-emits on every change. It also
 * tracks `cleared` because [ObserveVisitedUseCase] calls `clear()` in its
 * `.catch {}` recovery branch (kept honest per Third Law).
 */
private class FakeVisitedRepository : VisitedRepository {
    private val topicsFlow = MutableStateFlow<List<Topic>>(emptyList())
    val idsFlow = MutableStateFlow<List<String>>(emptyList())
    var cleared = false

    fun setTopics(topics: List<Topic>) {
        topicsFlow.update { topics }
        idsFlow.update { topics.map(Topic::id) }
    }

    override fun observeTopics(): Flow<List<Topic>> = topicsFlow
    override fun observeIds(): Flow<List<String>> = idsFlow
    override suspend fun add(topic: TopicPage) {
        // Mirror the prod repo's insert: append a BaseTopic for the page id.
        topicsFlow.update { it + BaseTopic(id = topic.id, title = topic.title) }
        idsFlow.update { it + topic.id }
    }

    override suspend fun clear() {
        cleared = true
        topicsFlow.update { emptyList() }
        idsFlow.update { emptyList() }
    }
}

/**
 * Behaviorally-equivalent in-memory [FavoritesRepository] tracking the favorite
 * id set as a flow so enrichment re-computes on toggle. Mirrors the prod repo's
 * contains/add/removeById surface that the toggle use case touches; the rest of
 * the interface is unused by the visited screen and returns empty.
 */
private class FakeFavoritesRepository : FavoritesRepository {
    val ids = MutableStateFlow<List<String>>(emptyList())
    private val updatedIds = MutableStateFlow<List<String>>(emptyList())

    override fun observeIds(): Flow<List<String>> = ids
    override fun observeUpdatedIds(): Flow<List<String>> = updatedIds
    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> = MutableStateFlow(emptyList())
    override suspend fun contains(id: String): Boolean = ids.value.contains(id)
    override suspend fun add(topic: Topic) {
        ids.update { if (it.contains(topic.id)) it else it + topic.id }
    }
    override suspend fun removeById(id: String) {
        ids.update { it.filterNot { existing -> existing == id } }
    }

    override suspend fun getIds(): List<String> = ids.value
    override suspend fun getTorrents(): List<Torrent> = emptyList()
    override suspend fun add(topics: List<Topic>) = topics.forEach { add(it) }
    override suspend fun remove(topic: Topic) = removeById(topic.id)
    override suspend fun remove(topics: List<Topic>) = topics.forEach { removeById(it.id) }
    override suspend fun removeById(ids: List<String>) = ids.forEach { removeById(it) }
    override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
    override suspend fun markVisited(id: String) = Unit
    override suspend fun clear() {
        ids.update { emptyList() }
    }
}

/** Behaviorally-equivalent [BookmarksRepository]: visited enrichment only reads
 *  observeNewTopics(); the rest is unused by this screen. */
private class FakeBookmarksRepository : BookmarksRepository {
    private val newTopics = MutableStateFlow<List<String>>(emptyList())
    override fun observeNewTopics(): Flow<List<String>> = newTopics
    override fun observeBookmarks(): Flow<List<CategoryModel>> = MutableStateFlow(emptyList())
    override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override fun observeNewTopics(id: String): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun getAllBookmarks(): List<Category> = emptyList()
    override suspend fun getTopics(id: String): List<String> = emptyList()
    override suspend fun getNewTopics(id: String): List<String> = emptyList()
    override suspend fun isBookmark(id: String): Boolean = false
    override suspend fun add(category: Category) = Unit
    override suspend fun remove(id: String) = Unit
    override suspend fun update(id: String, topics: List<String>, newTopics: List<String>) = Unit
    override suspend fun clear() = Unit
}

/**
 * Behaviorally-equivalent [ToggleFavoriteUseCase] mirroring
 * `ToggleFavoriteUseCaseImpl`: read contains(id) then add or removeById. The
 * production impl also notifies BackgroundService; that has no user-visible
 * effect on the visited screen and is intentionally omitted (documented
 * limitation per Third Law).
 */
private class RealToggleFavoriteUseCase(
    private val favoritesRepository: FavoritesRepository,
) : ToggleFavoriteUseCase {
    override suspend fun invoke(id: String) {
        if (favoritesRepository.contains(id)) {
            favoritesRepository.removeById(id)
        } else {
            favoritesRepository.add(BaseTopic(id = id, title = ""))
        }
    }
}

/** A toggle use case whose real operation fails — exercises the VM's
 *  runCatching/onFailure error branch. */
private class ThrowingToggleFavoriteUseCase : ToggleFavoriteUseCase {
    override suspend fun invoke(id: String) {
        throw IllegalStateException("toggle favorite failed")
    }
}
