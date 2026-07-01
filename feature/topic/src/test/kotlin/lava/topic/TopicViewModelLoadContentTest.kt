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
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.models.topic.TorrentData
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
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [TopicViewModel.loadTopic]'s content reducer — the
 * branch that decides which content surface the topic screen renders:
 *
 * ```
 * topicContent = if (torrentData != null) TopicContent.Torrent(title, torrentData)
 *                else                      TopicContent.Topic(title)
 * ```
 *
 * THE GAP (§6.N bluff-hunt, this cycle): the neighboring [TopicViewModelTest]
 * and [TopicViewModelHttpDownloadTest] both wire [TopicService.getTopicPage] to
 * return `torrentData = null` and NEVER assert on the resulting
 * `state.topicContent`. So the `if (torrentData != null)` branch had ZERO
 * coverage and the `else` branch's RESULTING STATE was never asserted — a bug
 * that swapped the branches, or dropped the title, or never reduced
 * `topicContent` at all, was invisible to the whole suite.
 *
 * The torrent vs. plain-topic distinction is user-visible: a [TopicContent.Torrent]
 * renders the torrent header + download affordance; a [TopicContent.Topic] does
 * not. Mis-rendering it means the download button vanishes for a real torrent
 * topic (or appears for a non-torrent one).
 *
 * Constitution (Second + Third Law): the SUT load path is the REAL
 * [GetTopicUseCase] → REAL [VisitTopicUseCase] wired to a behaviorally-
 * equivalent [LoadContentFakeTopicService] whose [TopicService.getTopicPage] returns a
 * configurable [TopicPage] (matching the production shape — the page CARRIES
 * `torrentData: TorrentData?` exactly as the real rutracker service does). No
 * UseCase is mocked. Boundary repos/services are in-memory fakes; the download
 * collaborators are outermost-boundary mocks never exercised by this load path
 * (same justification as the neighboring tests), because the SUT here is the
 * `loadTopic` content reducer.
 *
 * Primary assertion (Sixth Law clause 3): the user-visible `state.topicContent`
 * the topic screen renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelLoadContentTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val topicId = "98765"

    private lateinit var topicService: LoadContentFakeTopicService
    private lateinit var authService: TestAuthService

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
        topicService = LoadContentFakeTopicService(topicId)
        authService = TestAuthService()
    }

    private fun createViewModel(): TopicViewModel {
        val dispatchers = TestDispatchers(dispatcherRule.testDispatcher)
        val loggerFactory = TestLoggerFactory()
        val favoritesRepository = LoadContentFakeFavoritesRepository()
        val visitedRepository = LoadContentFakeVisitedRepository()
        val backgroundService = TestBackgroundService()

        val visitTopicUseCase =
            VisitTopicUseCase(visitedRepository, favoritesRepository, dispatchers)

        return TopicViewModel(
            savedStateHandle = SavedStateHandle(mapOf("t" to topicId)),
            addCommentUseCase = AddCommentUseCase(topicService, dispatchers),
            // Download collaborators are outermost-boundary deps, NOT the SUT for
            // the loadTopic content path; their real constructors pull in
            // :core:network types off this module's test classpath. Mocking is
            // permitted only here (boundary, not SUT) — the SUT is the real
            // GetTopicUseCase/VisitTopicUseCase chain wired above.
            downloadTorrentUseCase = mockk<DownloadTorrentUseCase>(relaxed = true),
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
     * CHALLENGE — a topic page that carries torrent data renders the
     * [TopicContent.Torrent] surface (the download-bearing layout), preserving
     * BOTH the title AND the torrent data the screen needs.
     *
     * Falsifiability rehearsal (PERFORMED this cycle — see report):
     *   Mutation: in TopicViewModel.loadTopic, invert the content branch so a
     *             non-null torrentData yields TopicContent.Topic(topic.title).
     *   Observed: this test FAILS — `state.topicContent` is a TopicContent.Topic,
     *             so the `assertTrue("... TopicContent.Torrent ...")` fires.
     *   Reverted: yes.
     */
    @Test
    fun `loadTopic with torrent data renders Torrent content carrying title and data`() =
        runTest(dispatcherRule.testDispatcher) {
            val data = TorrentData(
                tags = "1080p",
                posterUrl = null,
                status = null,
                date = null,
                size = "4.2 GB",
                seeds = 12,
                leeches = 3,
                magnetLink = "magnet:?xt=urn:btih:demo",
            )
            topicService.torrentData = data
            topicService.title = "Big Buck Bunny [1080p]"

            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                val content = awaitTopicContent()
                assertTrue(
                    "a topic WITH torrent data MUST render TopicContent.Torrent " +
                        "(the download-bearing surface), got $content",
                    content is TopicContent.Torrent,
                )
                content as TopicContent.Torrent
                assertEquals("Big Buck Bunny [1080p]", content.title)
                assertEquals(data, content.data)
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — a topic page with NO torrent data renders the plain
     * [TopicContent.Topic] surface (no download affordance), carrying the title.
     *
     * Falsifiability rehearsal (PERFORMED this cycle — see report):
     *   Mutation: in TopicViewModel.loadTopic, force the non-torrent branch to
     *             reduce TopicContent.Topic("") (drop topic.title).
     *   Observed: this test FAILS — assertEquals expected the real title but
     *             received "".
     *   Reverted: yes.
     */
    @Test
    fun `loadTopic without torrent data renders plain Topic content with title`() =
        runTest(dispatcherRule.testDispatcher) {
            topicService.torrentData = null
            topicService.title = "Discussion thread"

            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                val content = awaitTopicContent()
                assertTrue(
                    "a topic WITHOUT torrent data MUST render the plain " +
                        "TopicContent.Topic (no download surface), got $content",
                    content is TopicContent.Topic,
                )
                assertEquals("Discussion thread", (content as TopicContent.Topic).title)
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * Drains the onCreate collectors' interleaved state items until
     * `topicContent` has been reduced away from its [TopicContent.Initial]
     * default (i.e. loadTopic's reduce has run), returning that content.
     */
    private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<
        TopicState,
        TopicSideEffect,
        TopicViewModel,
        >.awaitTopicContent(): TopicContent {
        while (true) {
            when (val item = awaitItem()) {
                is org.orbitmvi.orbit.test.Item.StateItem ->
                    if (item.value.topicContent !is TopicContent.Initial) {
                        return item.value.topicContent
                    }
                is org.orbitmvi.orbit.test.Item.SideEffectItem -> Unit
            }
        }
    }
}

/**
 * Behaviorally-equivalent fake of [TopicService] for the loadTopic content path.
 * [getTopicPage] returns a [TopicPage] whose [TopicPage.torrentData] mirrors the
 * real service's nullable shape — the production rutracker service returns a
 * populated [TorrentData] for torrent topics and `null` for plain ones, which is
 * exactly the branch [TopicViewModel.loadTopic]'s content reducer keys off.
 */
private class LoadContentFakeTopicService(private val topicId: String) : TopicService {
    var torrentData: TorrentData? = null
    var title: String = "Fixture topic"

    private val emptyPage = Page<Post>(items = emptyList(), page = 1, pages = 1)

    override suspend fun getTopic(id: String): Topic =
        Torrent(id = id, title = title, author = null, category = null)

    override suspend fun getTopicPage(id: String, page: Int?, providerId: String?): TopicPage =
        TopicPage(
            id = id,
            title = title,
            author = null,
            category = null,
            torrentData = torrentData,
            commentsPage = emptyPage,
        )

    override suspend fun getCommentsPage(id: String, page: Int): Page<Post> = emptyPage

    override suspend fun addComment(topicId: String, message: String): Boolean = true
}

/** In-memory [FavoritesRepository] — never the SUT for the loadTopic path. */
private class LoadContentFakeFavoritesRepository : FavoritesRepository {
    private val ids = MutableStateFlow<List<String>>(emptyList())
    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> = flowOf(emptyList())
    override fun observeIds(): Flow<List<String>> = ids
    override fun observeUpdatedIds(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getIds(): List<String> = ids.value
    override suspend fun getTorrents(): List<Torrent> = emptyList()
    override suspend fun contains(id: String): Boolean = ids.value.contains(id)
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

/** In-memory [VisitedRepository] — never the SUT for the loadTopic path. */
private class LoadContentFakeVisitedRepository : VisitedRepository {
    override fun observeTopics(): Flow<List<Topic>> = flowOf(emptyList())
    override fun observeIds(): Flow<List<String>> = flowOf(emptyList())
    override fun observeProviderIds(): Flow<Map<String, String?>> = flowOf(emptyMap())
    override suspend fun add(topic: TopicPage, providerId: String?) = Unit
    override suspend fun clear() = Unit
}
