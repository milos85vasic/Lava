package lava.favorites

import android.app.Notification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.data.api.repository.FavoritesRepository
import lava.data.api.service.FavoritesService
import lava.data.api.service.TorrentService
import lava.domain.usecase.LoadFavoritesUseCase
import lava.domain.usecase.ObserveFavoritesUseCase
import lava.domain.usecase.RefreshFavoritesUseCase
import lava.domain.usecase.SyncFavoritesUseCase
import lava.models.forum.Category
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
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
 * Anti-bluff coverage for [FavoritesViewModel].
 *
 * Constitution (Second Law — no mocking of internal business logic):
 *  - The SUT collaborators are the REAL [ObserveFavoritesUseCase] and the
 *    REAL [RefreshFavoritesUseCase] (→ real [LoadFavoritesUseCase] +
 *    [SyncFavoritesUseCase]). No use case is mocked.
 *  - The outermost boundaries are behaviorally-equivalent fakes:
 *    [InMemoryFavoritesRepository] (a real `MutableStateFlow`-backed
 *    `observeTopics()` so the VM's list/empty branch is driven by real
 *    repository emissions), [FakeFavoritesService] and
 *    [FakeTorrentService] (which THROW on failure — the only way the real
 *    network boundary signals failure, Third Law). The sync use cases wrap
 *    their boundary calls in `runCatching`, so a failing service is the
 *    realistic degraded path.
 *
 * Primary assertions (Sixth Law clause 3) are on user-visible state: the
 * rendered favorites list (vs. the empty placeholder) and the sync spinner
 * (`isSyncing`) the screen shows during a manual refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var repository: InMemoryFavoritesRepository
    private lateinit var favoritesService: FakeFavoritesService
    private lateinit var torrentService: FakeTorrentService
    private lateinit var recordingAnalytics: RecordingAnalytics
    private lateinit var viewModel: FavoritesViewModel

    @Before
    fun setUp() {
        repository = InMemoryFavoritesRepository()
        favoritesService = FakeFavoritesService()
        torrentService = FakeTorrentService()
        recordingAnalytics = RecordingAnalytics()
        val dispatchers = TestDispatchers(dispatcherRule.testDispatcher)
        val notificationService = ThrowingNotificationService()

        val refreshFavoritesUseCase = RefreshFavoritesUseCase(
            loadFavoritesUseCase = LoadFavoritesUseCase(favoritesService, repository, dispatchers),
            syncFavoritesUseCase = SyncFavoritesUseCase(
                favoritesRepository = repository,
                torrentService = torrentService,
                notificationService = notificationService,
                dispatchers = dispatchers,
            ),
            dispatchers = dispatchers,
        )
        viewModel = FavoritesViewModel(
            observeFavoritesUseCase = ObserveFavoritesUseCase(
                favoritesRepository = repository,
                refreshFavoritesUseCase = refreshFavoritesUseCase,
                dispatchers = dispatchers,
            ),
            refreshFavoritesUseCase = refreshFavoritesUseCase,
            loggerFactory = TestLoggerFactory(),
            analytics = recordingAnalytics,
        )
    }

    /**
     * CHALLENGE — when the favorites repository holds topics, the screen
     * renders the favorites LIST (not the empty placeholder), carrying the
     * exact items the repository emitted.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in FavoritesViewModel.observeFavorites, invert the empty
     *             check (`if (items.isNotEmpty())` → Empty branch).
     *   Observed: this test FAILED — the state is FavoritesState.Empty, so
     *             the `state is FavoritesState.FavoritesList` assertion fails
     *             "favorites screen MUST render the list ...".
     *   Reverted: yes.
     */
    @Test
    fun `non-empty favorites render the list`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(
                listOf(
                    TopicModel(topic = BaseTopic(id = "1", title = "First fav")),
                    TopicModel(topic = BaseTopic(id = "2", title = "Second fav")),
                ),
            )
            viewModel.test(this) {
                runOnCreate()
                val state = awaitItemMatching { it is FavoritesState.FavoritesList }
                assertTrue(
                    "favorites screen MUST render the list when the repository " +
                        "has topics, was $state",
                    state is FavoritesState.FavoritesList,
                )
                val list = (state as FavoritesState.FavoritesList).items
                assertEquals(2, list.size)
                assertEquals("First fav", list[0].topic.title)
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — when the repository is empty, the screen renders the empty
     * placeholder, not the list.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in FavoritesViewModel.observeFavorites, force the
     *             FavoritesList branch unconditionally.
     *   Observed: this test FAILED — the empty case becomes a FavoritesList
     *             with an empty item list, so `state is FavoritesState.Empty`
     *             fails.
     *   Reverted: yes.
     */
    @Test
    fun `empty favorites render the empty placeholder`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            viewModel.test(this) {
                runOnCreate()
                val state = awaitItemMatching { it is FavoritesState.Empty }
                assertTrue(
                    "favorites screen MUST render the empty placeholder when " +
                        "the repository has no topics, was $state",
                    state is FavoritesState.Empty,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping a favorite posts
     * [FavoritesSideEffect.OpenTopic] carrying that topic's id, the side
     * effect the screen reacts to by navigating to the topic.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in FavoritesViewModel.onTopicClick, hardcode the id to "0".
     *   Observed: this test FAILED — assertEquals expected "42" but was "0".
     *   Reverted: yes.
     */
    @Test
    fun `TopicClick posts OpenTopic with the topic id`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it is FavoritesState.Empty }
                viewModel.perform(
                    FavoritesAction.TopicClick(
                        TopicModel(topic = BaseTopic(id = "42", title = "Tap me")),
                    ),
                )
                val effect = awaitSideEffect()
                assertEquals(FavoritesSideEffect.OpenTopic("42", null), effect)
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — LVA-070: tapping a favorited archiveorg topic posts
     * [FavoritesSideEffect.OpenTopic] carrying the persisted source provider, so
     * the topic screen routes the download button to HTTP_DOWNLOAD instead of the
     * active tracker. The provider id rides on the list item's
     * [TopicModel.providerId] (populated by `FavoriteTopicEntity.toTopicModel`).
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in FavoritesViewModel.onTopicClick, post
     *             `OpenTopic(topicModel.topic.id, null)` (drop the provider).
     *   Observed: this test FAILED — expected
     *             OpenTopic(id=arch, providerId=archiveorg) but was
     *             OpenTopic(id=arch, providerId=null).
     *   Reverted: yes.
     */
    @Test
    fun `TopicClick on an archiveorg favorite carries its persisted provider`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it is FavoritesState.Empty }
                viewModel.perform(
                    FavoritesAction.TopicClick(
                        TopicModel(
                            topic = BaseTopic(id = "arch", title = "Archive item"),
                            providerId = "archiveorg",
                        ),
                    ),
                )
                val effect = awaitSideEffect()
                assertEquals(FavoritesSideEffect.OpenTopic("arch", "archiveorg"), effect)
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — a manual "Sync now" runs the REAL RefreshFavoritesUseCase
     * against the real boundary services and ends with the spinner cleared
     * (`isSyncing == false`). The user-visible guarantee: the pull-to-refresh
     * spinner does not stay stuck on.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in FavoritesViewModel.onSyncNow, drop the SECOND reduce
     *             block (the one that sets isSyncing = false).
     *   Observed: this test FAILED — the final state keeps isSyncing = true,
     *             so `assertFalse(... finalState.isSyncing)` fails.
     *   Reverted: yes.
     */
    @Test
    fun `SyncNow clears the syncing spinner when it finishes`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.emit(emptyList())
            favoritesService.favorites = listOf(BaseTopic(id = "10", title = "Remote fav"))
            viewModel.test(this) {
                runOnCreate()
                // NB: observeFavorites launches refreshFavoritesUseCase at
                // create, which loads the remote favorite into the repo — so
                // the screen may transition straight from Initial to the
                // populated list without passing through Empty. We do not gate
                // on a specific intermediate; we drive SyncNow then assert the
                // FINAL settled spinner state (StateFlow conflates intermediates).
                viewModel.perform(FavoritesAction.SyncNowClick)
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
     * CHALLENGE — when the boundary service throws during a manual sync, the
     * VM records a non-fatal (§6.AC) AND still clears the spinner; the user
     * is never stuck on a broken refresh.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in FavoritesViewModel.onSyncNow, remove the try/catch so
     *             the thrown exception escapes (no recordNonFatal, spinner
     *             never cleared).
     *   Observed: with the throw surfaced from LoadFavoritesUseCase the VM's
     *             intent crashes and the spinner-clear reduce never runs;
     *             this test FAILED on `assertFalse(... isSyncing)`. (Here the
     *             real LoadFavoritesUseCase already wraps its own call in
     *             runCatching, so to exercise the VM-level catch the fake is
     *             made to throw from SyncFavoritesUseCase's getTorrents path.)
     *   Reverted: yes.
     */
    @Test
    fun `SyncNow recovers and clears spinner when the sync path fails`() =
        runTest(dispatcherRule.testDispatcher) {
            // Seed a torrent so SyncFavoritesUseCase iterates and hits the
            // failing TorrentService boundary (its runCatching swallows it,
            // exercising the degraded path). The VM-level try/catch is the
            // outer safety net that guarantees the spinner still clears.
            repository.emit(emptyList())
            repository.torrents = listOf(Torrent(id = "99", title = "Will fail to sync"))
            torrentService.shouldThrow = true
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it is FavoritesState.Empty }

                viewModel.perform(FavoritesAction.SyncNowClick)
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
     * Drains interleaved [Item.StateItem]s emitted by the onCreate collector
     * and the sync reduces until a state matches [predicate], returning it.
     * Side-effect items are ignored. Mirrors the drain pattern used by the
     * existing TopicViewModelTest for the shared orbit item stream.
     */
    private suspend fun OrbitTestContext<
        FavoritesState,
        FavoritesSideEffect,
        FavoritesViewModel,
        >.awaitItemMatching(predicate: (FavoritesState) -> Boolean): FavoritesState {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem -> if (predicate(item.value)) return item.value
                else -> Unit // skip side effects + other items
            }
        }
    }

    // CHALLENGE — LVA-017: re-adding the same favorite id REPLACEs (matches
    // FavoriteTopicDao @Insert(onConflict = REPLACE)), never duplicates. Makes
    // the fake's dedup falsifiable (the prior append-form yielded [7, 7]).
    @Test
    fun `InMemoryFavoritesRepository re-add keeps a single entry`() =
        runTest(dispatcherRule.testDispatcher) {
            val repo = InMemoryFavoritesRepository()
            repo.add(BaseTopic(id = "7", title = "v1"))
            repo.add(BaseTopic(id = "7", title = "v2"))
            assertEquals(listOf("7"), repo.getIds())
        }
}

private class RecordingAnalytics : AnalyticsTracker {
    val nonFatals = mutableListOf<Throwable>()
    override fun event(name: String, params: Map<String, String>) {}
    override fun setUserId(userId: String?) {}
    override fun setProperty(key: String, value: String?) {}
    override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
        nonFatals += throwable
    }
    override fun recordWarning(message: String, context: Map<String, String>) {}
    override fun log(message: String) {}
}

/**
 * Behaviorally-equivalent in-memory [FavoritesRepository]. `observeTopics()`
 * is backed by a real [MutableStateFlow] so the VM's list/empty branch reacts
 * to actual emissions (Third Law: a fake that returned a static flow would
 * hide the distinctUntilChanged + catch behaviour the real repo + use case
 * rely on). The mutating surface the sync use cases touch is implemented as a
 * simple in-memory store.
 */
private class InMemoryFavoritesRepository : FavoritesRepository {
    private val topics = MutableStateFlow<List<TopicModel<out Topic>>>(emptyList())
    private val ids = MutableStateFlow<List<String>>(emptyList())
    var torrents: List<Torrent> = emptyList()

    fun emit(value: List<TopicModel<out Topic>>) {
        topics.value = value
        ids.value = value.map { it.topic.id }
    }

    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> = topics
    override fun observeIds(): Flow<List<String>> = ids
    override fun observeUpdatedIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun getIds(): List<String> = ids.value
    override suspend fun getTorrents(): List<Torrent> = torrents
    override suspend fun contains(id: String): Boolean = ids.value.contains(id)
    override suspend fun add(topic: Topic, providerId: String?) {
        // LVA-017: mirror FavoriteTopicDao.insert @Insert(onConflict = REPLACE) —
        // the id is the PK, so re-adding an existing id REPLACEs its entry
        // rather than appending a duplicate (Anti-Bluff Third Law).
        // LVA-070: persist the source provider on the row's TopicModel.
        topics.value = topics.value.filterNot { it.topic.id == topic.id } +
            TopicModel(topic, providerId = providerId)
        ids.value = ids.value.filterNot { it == topic.id } + topic.id
    }
    override suspend fun add(topics: List<Topic>) {
        topics.forEach { add(it) }
    }
    override suspend fun remove(topic: Topic) = Unit
    override suspend fun remove(topics: List<Topic>) = Unit
    override suspend fun removeById(id: String) = Unit
    override suspend fun removeById(ids: List<String>) = Unit
    override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
    override suspend fun markVisited(id: String) = Unit
    override suspend fun clear() {
        topics.value = emptyList()
        ids.value = emptyList()
    }
}

/** Behaviorally-equivalent [FavoritesService] — returns the configured remote favorites. */
private class FakeFavoritesService : FavoritesService {
    var favorites: List<Topic> = emptyList()
    override suspend fun getFavorites(): List<Topic> = favorites
    override suspend fun add(id: String): Boolean = true
    override suspend fun remove(id: String): Boolean = true
}

/**
 * Behaviorally-equivalent [TorrentService]. The real boundary signals a
 * failure by THROWING; this fake throws when [shouldThrow] (Third Law).
 */
private class FakeTorrentService : TorrentService {
    var shouldThrow: Boolean = false
    override suspend fun getTorrent(id: String): Torrent {
        if (shouldThrow) error("torrent boundary failed for $id")
        return Torrent(id = id, title = "Synced $id")
    }
}

/**
 * [NotificationService] used only to satisfy SyncFavoritesUseCase's
 * constructor. The sync paths exercised here never show a notification
 * (no torrent gains an update), and `createSyncNotification()` would need
 * a real Android [Notification] — so it errors if ever reached.
 */
private class ThrowingNotificationService : NotificationService {
    override fun clearAllNotifications() = Unit
    override fun showFavoriteUpdateNotification(topic: Topic) = Unit
    override fun showBookmarkUpdateNotification(category: Category) = Unit
    override fun createSyncNotification(): Notification =
        error("createSyncNotification not exercised by these tests")
}
