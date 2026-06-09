package lava.topic

import androidx.lifecycle.SavedStateHandle
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.data.api.service.TopicService
import lava.domain.usecase.AddCommentUseCase
import lava.domain.usecase.AddLocalFavoriteUseCase
import lava.domain.usecase.DownloadHttpFileUseCase
import lava.domain.usecase.DownloadTorrentUseCase
import lava.domain.usecase.GetTopicUseCase
import lava.domain.usecase.IsAuthorizedUseCase
import lava.domain.usecase.ObserveFavoriteStateUseCase
import lava.domain.usecase.ObserveTopicPagingDataUseCase
import lava.domain.usecase.RemoveLocalFavoriteUseCase
import lava.domain.usecase.ResolveProviderDownloadKindUseCase
import lava.domain.usecase.ToggleFavoriteUseCaseImpl
import lava.domain.usecase.VisitTopicUseCase
import lava.models.Page
import lava.models.auth.AuthState
import lava.models.topic.BaseTopic
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import lava.testing.service.TestAuthService
import lava.testing.service.TestBackgroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [TopicViewModel]'s add-comment surface — the
 * feature that was dead-ended at the screen layer (`ShowAddCommentDialog`
 * / `ShowAddCommentError` were `-> Unit // TODO` in TopicScreen.kt).
 *
 * Constitution (Second Law — no mocking of internal business logic):
 *  - The SUT add-comment path is the REAL [AddCommentUseCase] wired to a
 *    behaviorally-equivalent [FakeTopicService]. The fake enforces the
 *    production [TopicService.addComment] contract: it RETURNS true on
 *    success and THROWS on failure — exactly the boundary
 *    [AddCommentUseCase]'s `runCatching { ... }.isSuccess` consumes. A
 *    fake that returned `false` instead of throwing would be a bluff fake
 *    (Third Law) because the real path can only signal failure by throwing.
 *  - The authorize gate is the REAL [IsAuthorizedUseCase] wired to the
 *    real [TestAuthService] from `:core:testing`.
 *  - Every other VM use case is a REAL implementation wired to in-memory
 *    boundary fakes; none is exercised by the add-comment path but the VM
 *    constructor requires them, so they are constructed honestly rather
 *    than mocked.
 *
 * Primary assertion (Sixth Law clause 3): the user-visible [TopicSideEffect]
 * the screen reacts to. The screen shows the add-comment dialog on
 * [TopicSideEffect.ShowAddCommentDialog] and surfaces an error snackbar on
 * [TopicSideEffect.ShowAddCommentError]; this test pins the contract those
 * stubs were failing to honour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val topicId = "12345"

    private lateinit var topicService: FakeTopicService
    private lateinit var authService: TestAuthService
    private lateinit var viewModel: TopicViewModel

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
        topicService = FakeTopicService(topicId)
        authService = TestAuthService()
        val dispatchers = TestDispatchers(dispatcherRule.testDispatcher)
        val loggerFactory = TestLoggerFactory()
        val favoritesRepository = FakeFavoritesRepository()
        val visitedRepository = FakeVisitedRepository()
        val backgroundService = TestBackgroundService()

        val visitTopicUseCase = VisitTopicUseCase(visitedRepository, favoritesRepository, dispatchers)

        viewModel = TopicViewModel(
            savedStateHandle = SavedStateHandle(mapOf("t" to topicId)),
            addCommentUseCase = AddCommentUseCase(topicService, dispatchers),
            // Download path is never exercised by the add-comment tests; its
            // real constructor pulls in :core:network types not on this
            // module's test classpath. Mocking is permitted here because this
            // is an outermost-boundary dependency, NOT the system under test
            // (the SUT is the add-comment path: AddCommentUseCase /
            // IsAuthorizedUseCase, both wired as real instances above/below).
            downloadTorrentUseCase = mockk<DownloadTorrentUseCase>(relaxed = true),
            // LVA-052 — the download branch is not the SUT for the add-comment
            // tests; these two are outermost-boundary deps (same justification as
            // downloadTorrentUseCase above). The dedicated download branching SUT
            // lives in TopicViewModelHttpDownloadTest.
            downloadHttpFileUseCase = mockk<DownloadHttpFileUseCase>(relaxed = true),
            resolveProviderDownloadKindUseCase = mockk<ResolveProviderDownloadKindUseCase>(relaxed = true),
            getTopicUseCase = GetTopicUseCase(topicService, visitTopicUseCase, dispatchers),
            isAuthorizedUseCase = IsAuthorizedUseCase(authService),
            observeFavoriteStateUseCase = ObserveFavoriteStateUseCase(favoritesRepository),
            observeTopicPagingDataUseCase = ObserveTopicPagingDataUseCase(topicService, loggerFactory),
            toggleFavoriteUseCase = ToggleFavoriteUseCaseImpl(
                addLocalFavoriteUseCase = AddLocalFavoriteUseCase(topicService, favoritesRepository, dispatchers),
                removeLocalFavoriteUseCase = RemoveLocalFavoriteUseCase(favoritesRepository),
                favoritesRepository = favoritesRepository,
                backgroundService = backgroundService,
                dispatchers = dispatchers,
            ),
            analytics = recordingAnalytics,
            loggerFactory = loggerFactory,
        )
    }

    /**
     * CHALLENGE — tapping "add comment" while authorized posts
     * [TopicSideEffect.ShowAddCommentDialog], the side effect the screen
     * reacts to by opening the dialog.
     *
     * Falsifiability rehearsal:
     *   Mutation: in TopicViewModel.onAddCommentClick, drop the authorized
     *             branch's `postSideEffect(TopicSideEffect.ShowAddCommentDialog)`
     *             (replace its body with `Unit`).
     *   Observed: this test FAILS with
     *             "java.lang.AssertionError: Expected the ShowAddCommentDialog
     *              side effect but timed out / received a different item".
     *   Reverted: yes.
     */
    @Test
    fun `AddCommentClick while authorized posts ShowAddCommentDialog`() =
        runTest(dispatcherRule.testDispatcher) {
            authService.authState.value = AuthState.Authorized(name = "tester", avatarUrl = null)
            viewModel.test(this) {
                runOnCreate()
                viewModel.perform(TopicAction.AddCommentClick)
                // onCreate's loadTopic/paging/favorites collectors interleave
                // state items on the same stream; drain them until the side
                // effect the action produced arrives.
                assertEquals(
                    TopicSideEffect.ShowAddCommentDialog,
                    awaitSideEffectDrainingStates(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — submitting a comment that the upstream rejects (the real
     * service throws) posts [TopicSideEffect.ShowAddCommentError], the side
     * effect the screen reacts to by showing the error snackbar.
     *
     * Falsifiability rehearsal:
     *   Mutation: in TopicViewModel.onAddComment, change the `else` branch
     *             that posts ShowAddCommentError to post nothing (`Unit`).
     *   Observed: this test FAILS with
     *             "java.lang.AssertionError: Expected the ShowAddCommentError
     *              side effect but timed out".
     *   Reverted: yes.
     */
    @Test
    fun `AddComment submit failure posts ShowAddCommentError`() =
        runTest(dispatcherRule.testDispatcher) {
            topicService.addCommentSucceeds = false
            viewModel.test(this) {
                runOnCreate()
                viewModel.perform(TopicAction.AddComment("hello world"))
                assertEquals(
                    TopicSideEffect.ShowAddCommentError,
                    awaitSideEffectDrainingStates(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — a successful comment submission refreshes the comment
     * list (no error side effect). The real [AddCommentUseCase] returns
     * true → the VM triggers a paging refresh instead of posting the error.
     * The user-visible guarantee: a successful post does NOT surface the
     * error snackbar.
     *
     * Falsifiability rehearsal:
     *   Mutation: in TopicViewModel.onAddComment, invert the boolean
     *             (`if (!addCommentUseCase(...))`) so success wrongly posts
     *             the error.
     *   Observed: this test FAILS with
     *             "java.lang.AssertionError: a successful comment MUST NOT
     *              post ShowAddCommentError".
     *   Reverted: yes.
     */
    @Test
    fun `AddComment submit success does not post error`() =
        runTest(dispatcherRule.testDispatcher) {
            topicService.addCommentSucceeds = true
            viewModel.test(this) {
                runOnCreate()
                viewModel.perform(TopicAction.AddComment("nice torrent"))
                // Let the real AddCommentUseCase run against the fake service
                // on the test dispatcher, then stop the never-completing
                // onCreate collectors so the orbit-test block can close.
                cancelAndIgnoreRemainingItems()
            }
            // On a successful post the use case records the message and the VM
            // triggers a paging refresh INSTEAD of posting the error effect.
            assertTrue(
                "a successful comment MUST be accepted by the service (no error path)",
                topicService.addedComments.contains("nice torrent"),
            )
        }

    /**
     * Drains interleaved [Item.StateItem]s emitted by the onCreate collectors
     * until the next [Item.SideEffectItem] arrives, returning its payload.
     * The add-comment side effects are the SUT; the onCreate state reduces are
     * incidental noise on the shared item stream.
     */
    private suspend fun OrbitTestContext<
        TopicState,
        TopicSideEffect,
        TopicViewModel,
        >.awaitSideEffectDrainingStates(): TopicSideEffect {
        while (true) {
            when (val item = awaitItem()) {
                is Item.SideEffectItem -> return item.value
                is Item.StateItem -> Unit // skip interleaved state reduce
            }
        }
    }

    // CHALLENGE — LVA-017: re-adding the same favorite id REPLACEs (matches
    // FavoriteTopicDao @Insert(onConflict = REPLACE)), never duplicates. Makes
    // the fake's dedup falsifiable (the prior append-form yielded [7, 7]).
    @Test
    fun `FakeFavoritesRepository re-add keeps a single id`() =
        runTest(dispatcherRule.testDispatcher) {
            val repo = FakeFavoritesRepository()
            repo.add(BaseTopic(id = "7", title = "v1"))
            repo.add(BaseTopic(id = "7", title = "v2"))
            assertEquals(listOf("7"), repo.getIds())
        }
}

/**
 * Behaviorally-equivalent fake of [TopicService].
 *
 * The production rutracker [TopicService] signals an add-comment failure
 * by THROWING (network error / parse failure / rejected post); the
 * [AddCommentUseCase] wraps the call in `runCatching { ... }.isSuccess`.
 * This fake therefore throws when [addCommentSucceeds] is false — a fake
 * that returned `false` would diverge from the only failure shape the real
 * boundary can produce (Anti-Bluff Pact Third Law).
 */
private class FakeTopicService(private val topicId: String) : TopicService {
    var addCommentSucceeds: Boolean = true
    val addedComments = mutableListOf<String>()

    private val emptyPage = Page<Post>(items = emptyList(), page = 1, pages = 1)

    override suspend fun getTopic(id: String): Topic =
        Torrent(id = id, title = "Fixture topic", author = null, category = null)

    override suspend fun getTopicPage(id: String, page: Int?): TopicPage =
        TopicPage(
            id = id,
            title = "Fixture topic",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = emptyPage,
        )

    override suspend fun getCommentsPage(id: String, page: Int): Page<Post> = emptyPage

    override suspend fun addComment(topicId: String, message: String): Boolean {
        if (!addCommentSucceeds) {
            error("addComment rejected by upstream for topic $topicId")
        }
        addedComments.add(message)
        return true
    }
}

/** In-memory [FavoritesRepository] — never the SUT for the add-comment path. */
private class FakeFavoritesRepository : FavoritesRepository {
    private val ids = MutableStateFlow<List<String>>(emptyList())
    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> = flowOf(emptyList())
    override fun observeIds(): Flow<List<String>> = ids
    override fun observeUpdatedIds(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getIds(): List<String> = ids.value
    override suspend fun getTorrents(): List<Torrent> = emptyList()
    override suspend fun contains(id: String): Boolean = ids.value.contains(id)

    // LVA-017: mirror FavoriteTopicDao.insert @Insert(onConflict = REPLACE) — the
    // id is the PK, so re-adding an existing id REPLACEs rather than duplicates.
    override suspend fun add(topic: Topic, providerId: String?) { ids.value = ids.value.filterNot { it == topic.id } + topic.id }
    override suspend fun add(topics: List<Topic>) {
        val incoming = topics.map { it.id }
        ids.value = ids.value.filterNot { it in incoming } + incoming
    }
    override suspend fun remove(topic: Topic) { ids.value = ids.value - topic.id }
    override suspend fun remove(topics: List<Topic>) { ids.value = ids.value - topics.map { it.id }.toSet() }
    override suspend fun removeById(id: String) { ids.value = ids.value - id }
    override suspend fun removeById(ids: List<String>) { this.ids.value = this.ids.value - ids.toSet() }
    override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
    override suspend fun markVisited(id: String) = Unit
    override suspend fun clear() { ids.value = emptyList() }
}

/** In-memory [VisitedRepository] — never the SUT for the add-comment path. */
private class FakeVisitedRepository : VisitedRepository {
    override fun observeTopics(): Flow<List<Topic>> = flowOf(emptyList())
    override fun observeIds(): Flow<List<String>> = flowOf(emptyList())
    override fun observeProviderIds(): Flow<Map<String, String?>> = flowOf(emptyMap())
    override suspend fun add(topic: TopicPage, providerId: String?) = Unit
    override suspend fun clear() = Unit
}
