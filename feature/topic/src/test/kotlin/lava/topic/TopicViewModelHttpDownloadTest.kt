package lava.topic

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import lava.auth.api.TokenProvider
import lava.common.analytics.AnalyticsTracker
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.data.api.service.TopicService
import lava.dispatchers.api.Dispatchers
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
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.downloads.api.HttpFileDownloadRequest
import lava.models.Page
import lava.models.auth.AuthState
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.network.api.HttpArtifact
import lava.network.api.HttpDownloadSource
import lava.network.api.ProviderCapabilitySource
import lava.network.api.ProviderDownloadKind
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import lava.testing.service.TestAuthService
import lava.testing.service.TestBackgroundService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test

/**
 * LVA-052 — real-stack coverage that the topic download action is USER-REACHABLE
 * for HTTP_DOWNLOAD providers (archiveorg / gutenberg) AND that the rutracker
 * `.torrent` path is preserved unchanged.
 *
 * The SUT is the REAL [TopicViewModel] branch
 * ([TopicViewModel.onTorrentFileClick]) wired to the REAL
 * [ResolveProviderDownloadKindUseCase] + REAL [DownloadHttpFileUseCase] + REAL
 * [DownloadTorrentUseCase] (Second Law — no mocking of internal business logic).
 * Only the boundaries BELOW those use cases are faked, and each fake enforces
 * the production contract:
 *
 *  - [HttpTestDownloadSource] — the network seam the SDK sits behind. Returns a
 *    real [HttpArtifact] (bytes + filename) for the configured provider, exactly
 *    as `HttpDownloadSourceImpl` would after a real HTTP fetch.
 *  - [HttpTestProviderCapabilitySource] — maps a provider id to its download kind,
 *    mirroring `ProviderCapabilitySourceImpl`'s read of the SDK descriptor
 *    capabilities (HTTP_DOWNLOAD-and-not-TORRENT ⇒ HTTP; TORRENT ⇒ TORRENT).
 *  - [RecordingDownloadService] — the disk writer. It records the exact bytes /
 *    request handed to it so the test can assert the user-visible on-disk
 *    artifact, the Sixth-Law clause-3 primary assertion (a file written to disk).
 *
 * Primary assertions are on user-visible state: the [DownloadState.Completed]
 * URI the screen renders AND the bytes/filename that reach the disk writer.
 *
 * ## Falsifiability rehearsal (§6.T.1 / §6.J clause 2)
 * Recorded in the commit body Bluff-Audit stamp. Two mutations were performed
 * before the fix and observed to fail:
 *   1. `ProviderCapabilitySourceImpl.downloadKind` forced to always return
 *      `TORRENT` → the archiveorg test routes to the `.torrent` path; the disk
 *      writer never receives the HTTP bytes; the HTTP assertion FAILS.
 *   2. `TopicViewModel.onTorrentFileClick` HTTP branch wired to
 *      `downloadTorrentFile(...)` → same failure shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelHttpDownloadTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val topicId = "moby-dick/mobydick.epub"
    private val archiveProviderId = "archiveorg"
    private val rutrackerProviderId = "rutracker"

    private val recordingAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    private fun viewModel(
        providerIdArg: String?,
        capabilities: Map<String, ProviderDownloadKind>,
        httpSource: HttpDownloadSource,
        downloadService: DownloadService,
    ): TopicViewModel {
        val dispatchers: Dispatchers = TestDispatchers(dispatcherRule.testDispatcher)
        val loggerFactory = TestLoggerFactory()
        val topicService = HttpTestTopicService(topicId)
        val favoritesRepository = HttpTestFavoritesRepository()
        val visitedRepository = HttpTestVisitedRepository()
        val authService = TestAuthService().apply {
            // Authorized so the `.torrent` no-regression path is not short-circuited
            // by the login gate; the HTTP path does not consult auth at all.
            authState.value = AuthState.Authorized(name = "tester", avatarUrl = null)
        }
        val backgroundService = TestBackgroundService()
        val visitTopicUseCase = VisitTopicUseCase(visitedRepository, favoritesRepository, dispatchers)

        val capabilitySource = HttpTestProviderCapabilitySource(capabilities)

        return TopicViewModel(
            savedStateHandle = SavedStateHandle(
                buildMap {
                    put("t", topicId)
                    if (providerIdArg != null) put("p", providerIdArg)
                },
            ),
            addCommentUseCase = AddCommentUseCase(topicService, dispatchers),
            downloadTorrentUseCase = DownloadTorrentUseCase(
                networkApiRepository = HttpTestNetworkApiRepository(),
                downloadService = downloadService,
                tokenProvider = HttpTestTokenProvider(),
                dispatchers = dispatchers,
            ),
            downloadHttpFileUseCase = DownloadHttpFileUseCase(httpSource, downloadService, dispatchers),
            resolveProviderDownloadKindUseCase = ResolveProviderDownloadKindUseCase(capabilitySource, dispatchers),
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

    // CHALLENGE — an archiveorg (HTTP_DOWNLOAD) topic routes the download button
    // to the HTTP-file path: the exact fetched bytes reach the disk writer and
    // the screen renders DownloadState.Completed with the saved URI.
    @Test
    fun `archiveorg HTTP_DOWNLOAD topic downloads the real file to disk`() =
        runTest(dispatcherRule.testDispatcher) {
            val expectedBytes = "EPUB-BYTES-on-the-wire".toByteArray()
            val httpSource = HttpTestDownloadSource(
                trackerId = archiveProviderId,
                artifact = HttpArtifact(
                    bytes = expectedBytes,
                    sourceUrl = "https://archive.org/download/$topicId",
                    fileName = "mobydick.epub",
                ),
            )
            val disk = RecordingDownloadService(savedUri = "content://downloads/mobydick.epub")
            val vm = viewModel(
                providerIdArg = archiveProviderId,
                capabilities = mapOf(archiveProviderId to ProviderDownloadKind.HTTP),
                httpSource = httpSource,
                downloadService = disk,
            )

            vm.test(this) {
                runOnCreate()
                vm.perform(TopicAction.TorrentFileClick(title = "Moby Dick"))
                val completed = awaitDownloadCompletedDrainingOthers()
                // Primary (Sixth Law clause 3) — the URI the screen renders.
                assertEquals("content://downloads/mobydick.epub", completed.uri)
                cancelAndIgnoreRemainingItems()
            }

            // Primary (Sixth Law clause 3) — the exact bytes + filename that
            // reached the disk writer (the user-visible on-disk artifact).
            val written = requireNotNull(disk.httpRequest) {
                "the HTTP-file path MUST hand the fetched bytes to the disk writer"
            }
            assertArrayEquals("bytes written to disk must equal the fetched bytes", expectedBytes, written.bytes)
            assertEquals("mobydick.epub", written.fileName)
            // The `.torrent` path MUST NOT have run for an HTTP_DOWNLOAD provider.
            assertNull("a `.torrent` request MUST NOT be issued for an HTTP provider", disk.torrentRequest)
        }

    // CHALLENGE — no-regression: a rutracker (TORRENT_DOWNLOAD) topic STILL takes
    // the `.torrent` path; the HTTP-file path is not touched.
    @Test
    fun `rutracker TORRENT topic still uses the torrent path`() =
        runTest(dispatcherRule.testDispatcher) {
            val httpSource = HttpTestDownloadSource(
                trackerId = archiveProviderId, // deliberately NOT rutracker — proves the HTTP path is untaken
                artifact = HttpArtifact("x".toByteArray(), "https://x", "x"),
            )
            val disk = RecordingDownloadService(savedUri = "content://downloads/file.torrent")
            val vm = viewModel(
                providerIdArg = rutrackerProviderId,
                capabilities = mapOf(rutrackerProviderId to ProviderDownloadKind.TORRENT),
                httpSource = httpSource,
                downloadService = disk,
            )

            vm.test(this) {
                runOnCreate()
                vm.perform(TopicAction.TorrentFileClick(title = "Some Linux ISO"))
                val completed = awaitDownloadCompletedDrainingOthers()
                assertEquals("content://downloads/file.torrent", completed.uri)
                cancelAndIgnoreRemainingItems()
            }

            // The `.torrent` writer ran with the right title.
            val torrent = requireNotNull(disk.torrentRequest) {
                "the rutracker path MUST issue a `.torrent` download request"
            }
            assertEquals(topicId, torrent.id)
            assertEquals("Some Linux ISO", torrent.title)
            // The HTTP-file writer + the HTTP source MUST NOT have run.
            assertNull("the HTTP-file path MUST NOT run for a TORRENT provider", disk.httpRequest)
            assertFalse("the HTTP source MUST NOT be queried for a TORRENT provider", httpSource.wasQueried)
        }

    private suspend fun OrbitTestContext<
        TopicState,
        TopicSideEffect,
        TopicViewModel,
        >.awaitDownloadCompletedDrainingOthers(): DownloadState.Completed {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem ->
                    (item.value.downloadState as? DownloadState.Completed)?.let { return it }
                is Item.SideEffectItem -> Unit // ShowDownloadProgress etc. — incidental
            }
        }
    }
}

// ---- Behaviorally-equivalent boundary fakes (all BELOW the SUT use cases) ----

/**
 * Network seam fake mirroring `HttpDownloadSourceImpl`: returns the artifact ONLY
 * for the matching tracker id (Capability Honesty), null otherwise — never a
 * fabricated artifact.
 */
private class HttpTestDownloadSource(
    private val trackerId: String,
    private val artifact: HttpArtifact,
) : HttpDownloadSource {
    var wasQueried: Boolean = false
        private set

    override suspend fun downloadHttpFile(trackerId: String, id: String): HttpArtifact? {
        wasQueried = true
        return if (trackerId == this.trackerId) artifact else null
    }
}

/**
 * Capability seam fake mirroring `ProviderCapabilitySourceImpl`'s descriptor read.
 * Unknown providers resolve to NONE (the legacy `.torrent` fallback).
 */
private class HttpTestProviderCapabilitySource(
    private val kinds: Map<String, ProviderDownloadKind>,
) : ProviderCapabilitySource {
    override suspend fun downloadKind(trackerId: String): ProviderDownloadKind =
        kinds[trackerId] ?: ProviderDownloadKind.NONE
}

/** Disk writer that records exactly what it was asked to persist. */
private class RecordingDownloadService(private val savedUri: String) : DownloadService {
    var torrentRequest: DownloadRequest? = null
        private set
    var httpRequest: HttpFileDownloadRequest? = null
        private set

    override suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String? {
        torrentRequest = downloadRequest
        return savedUri
    }

    override suspend fun downloadHttpFile(downloadRequest: HttpFileDownloadRequest): String? {
        httpRequest = downloadRequest
        return savedUri
    }
}

private class HttpTestNetworkApiRepository : lava.network.data.NetworkApiRepository {
    override suspend fun getApi(): lava.network.api.NetworkApi = error("unused in this test")
    override suspend fun getCaptchaUrl(url: String): String = url
    override suspend fun getDownloadUri(id: String): String = "https://rutracker.org/forum/dl.php?t=$id"
    override suspend fun getAuthHeader(token: String): Pair<String, String> = "Cookie" to "bb_session=$token"
}

private class HttpTestTokenProvider : TokenProvider {
    override suspend fun getToken(): String = "test-token"
    override suspend fun refreshToken(): Boolean = true
}

/** In-memory [TopicService] sufficient for VM onCreate (load topic + paging). */
private class HttpTestTopicService(private val topicId: String) : TopicService {
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

    override suspend fun addComment(topicId: String, message: String): Boolean = true
}

private class HttpTestFavoritesRepository : FavoritesRepository {
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

private class HttpTestVisitedRepository : VisitedRepository {
    override fun observeTopics(): Flow<List<Topic>> = flowOf(emptyList())
    override fun observeIds(): Flow<List<String>> = flowOf(emptyList())
    override fun observeProviderIds(): Flow<Map<String, String?>> = flowOf(emptyMap())
    override suspend fun add(topic: TopicPage, providerId: String?) = Unit
    override suspend fun clear() = Unit
}
