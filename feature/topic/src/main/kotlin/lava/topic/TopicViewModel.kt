package lava.topic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import lava.common.analytics.AnalyticsTracker
import lava.common.analytics.rethrowIfCancellation
import lava.domain.model.PagingAction
import lava.domain.model.refresh
import lava.domain.model.retry
import lava.domain.usecase.AddCommentUseCase
import lava.domain.usecase.DownloadHttpFileUseCase
import lava.domain.usecase.DownloadTorrentUseCase
import lava.domain.usecase.GetTopicUseCase
import lava.domain.usecase.IsAuthorizedUseCase
import lava.domain.usecase.ObserveFavoriteStateUseCase
import lava.domain.usecase.ObserveTopicPagingDataUseCase
import lava.domain.usecase.ResolveProviderDownloadKindUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.logger.api.LoggerFactory
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.topic.Author
import lava.network.api.ProviderDownloadKind
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class TopicViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val addCommentUseCase: AddCommentUseCase,
    private val downloadTorrentUseCase: DownloadTorrentUseCase,
    private val downloadHttpFileUseCase: DownloadHttpFileUseCase,
    private val resolveProviderDownloadKindUseCase: ResolveProviderDownloadKindUseCase,
    private val getTopicUseCase: GetTopicUseCase,
    private val isAuthorizedUseCase: IsAuthorizedUseCase,
    private val observeFavoriteStateUseCase: ObserveFavoriteStateUseCase,
    private val observeTopicPagingDataUseCase: ObserveTopicPagingDataUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val analytics: AnalyticsTracker,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<TopicState, TopicSideEffect> {
    private val logger = loggerFactory.get("OpenTopicViewModel")
    private val id = savedStateHandle.id

    // LVA-052 — source-provider id (null for favorites/visited/deep-link, which
    // can't supply one yet). Empty string ⇒ the resolve use case falls back to
    // the active tracker, preserving legacy single-tracker behaviour.
    private val providerId = savedStateHandle.providerId.orEmpty()
    private val pagingActions = MutableSharedFlow<PagingAction>()

    override val container: Container<TopicState, TopicSideEffect> = container(
        initialState = TopicState(),
        onCreate = {
            loadTopic()
            observePagingData()
            observeFavoritesState()
        },
    )

    fun perform(action: TopicAction): Any {
        logger.d { "Perform $action" }
        return when (action) {
            is TopicAction.AddComment -> onAddComment(action.comment)
            is TopicAction.AddCommentClick -> onAddCommentClick()
            is TopicAction.AuthorClick -> onAuthorClick(action.author)
            is TopicAction.BackClick -> onBackClick()
            is TopicAction.CategoryClick -> onCategoryClick(action.category)
            is TopicAction.FavoriteClick -> onFavoriteClick()
            is TopicAction.GoToPage -> onGoToPage(action.page)
            is TopicAction.LoginClick -> onLoginClick()
            is TopicAction.MagnetClick -> onMagnetClick(action.link)
            is TopicAction.OpenFileClick -> onOpenFileClick(action.uri)
            is TopicAction.ShareClick -> onShareClick()
            is TopicAction.RetryClick -> onRetryClick()
            is TopicAction.TorrentFileClick -> onTorrentFileClick(action.title)
        }
    }

    private fun loadTopic() = intent {
        analytics.event(
            AnalyticsTracker.Events.VIEW_TOPIC,
            mapOf(AnalyticsTracker.Params.TOPIC_ID to id.toString()),
        )
        runCatching {
            coroutineScope {
                // LVA-070 — pass the source provider so the visited record
                // persists it (HTTP_DOWNLOAD routing for archiveorg/gutenberg).
                getTopicUseCase(id, providerId.ifBlank { null })
            }
        }
            .onSuccess { topic ->
                reduce {
                    val torrentData = topic.torrentData
                    state.copy(
                        topicContent = if (torrentData != null) {
                            TopicContent.Torrent(
                                title = topic.title,
                                data = torrentData,
                            )
                        } else {
                            TopicContent.Topic(topic.title)
                        },
                    )
                }
            }
            .onFailure { err ->
                err.rethrowIfCancellation()
                analytics.recordNonFatal(
                    err,
                    mapOf(
                        AnalyticsTracker.Params.TOPIC_ID to id.toString(),
                        AnalyticsTracker.Params.ERROR to "load_topic_failed",
                    ),
                )
            }
    }

    private fun observeFavoritesState() = intent {
        observeFavoriteStateUseCase(id).collectLatest { isFavorite ->
            val favoriteState = TopicFavoriteState.FavoriteState(isFavorite)
            reduce { state.copy(favoriteState = favoriteState) }
        }
    }

    private fun observePagingData() = intent {
        observeTopicPagingDataUseCase(
            id = id,
            actions = pagingActions,
            scope = viewModelScope,
        ).collectLatest { (data, loadStates, pagination) ->
            reduce {
                state.copy(
                    paginationState = if (pagination.totalPages > 1) {
                        PaginationState.Pagination(
                            page = pagination.loadedPages.first,
                            totalPages = pagination.totalPages,
                        )
                    } else {
                        PaginationState.NoPagination
                    },
                    commentsContent = when {
                        data == null -> CommentsContent.Initial
                        data.isEmpty() -> CommentsContent.Empty
                        else -> CommentsContent.Posts(data)
                    },
                    loadStates = loadStates,
                )
            }
        }
    }

    private fun onAddComment(comment: String) = intent {
        if (addCommentUseCase(id, comment)) {
            pagingActions.refresh()
        } else {
            postSideEffect(TopicSideEffect.ShowAddCommentError)
        }
    }

    private fun onAddCommentClick() = intent {
        if (isAuthorizedUseCase()) {
            postSideEffect(TopicSideEffect.ShowAddCommentDialog)
        } else {
            postSideEffect(TopicSideEffect.ShowLoginRequired)
        }
    }

    private fun onBackClick() = intent {
        postSideEffect(TopicSideEffect.Back)
    }

    private fun onFavoriteClick() = intent {
        // LVA-070 — pass the source provider so an archiveorg/gutenberg favorite
        // persists its provider id and later routes to HTTP_DOWNLOAD.
        runCatching { toggleFavoriteUseCase(id, providerId.ifBlank { null }) }
            .onFailure {
                it.rethrowIfCancellation()
                analytics.recordNonFatal(
                    it,
                    mapOf(AnalyticsTracker.Params.TOPIC_ID to id.toString()),
                )
                postSideEffect(TopicSideEffect.ShowFavoriteToggleError)
            }
    }

    private fun onGoToPage(page: Int) = intent {
        pagingActions.refresh(page)
    }

    private fun onShareClick() = intent {
        val link = createShareLink()
        postSideEffect(TopicSideEffect.ShareLink(link))
    }

    private fun onRetryClick() = intent {
        pagingActions.retry()
    }

    private fun onLoginClick() = intent {
        postSideEffect(TopicSideEffect.OpenLogin)
    }

    private fun onAuthorClick(author: Author) = intent {
        postSideEffect(TopicSideEffect.OpenSearch(Filter(author = author)))
    }

    private fun onCategoryClick(category: Category) = intent {
        postSideEffect(TopicSideEffect.OpenCategory(category.id))
    }

    private fun onMagnetClick(link: String) = intent {
        postSideEffect(TopicSideEffect.ShowMagnet(link))
    }

    private fun onTorrentFileClick(title: String) = intent {
        // LVA-052 — branch on the SOURCE provider's download shape so the topic
        // download button reaches HTTP_DOWNLOAD providers (archiveorg /
        // gutenberg → real file on disk) as well as `.torrent` providers
        // (rutracker / rutor). The active descriptor's capability set is the
        // single source of truth (Capability Honesty, 6.E) — resolved off the
        // tracker SDK via the network seam, never a hardcoded id (§6.R).
        when (resolveProviderDownloadKindUseCase(providerId)) {
            ProviderDownloadKind.HTTP -> downloadHttpFile()
            // TORRENT and NONE both take the legacy `.torrent` path: NONE keeps
            // the prior behaviour (the download button only shows for torrent
            // topics today), and the `.torrent` use case returns null on
            // failure → DownloadState.Error, which the screen already renders.
            ProviderDownloadKind.TORRENT,
            ProviderDownloadKind.NONE,
            -> downloadTorrentFile(title)
        }
    }

    private fun downloadTorrentFile(title: String) = intent {
        if (isAuthorizedUseCase()) {
            analytics.event(
                AnalyticsTracker.Events.DOWNLOAD_TORRENT,
                mapOf(AnalyticsTracker.Params.TOPIC_ID to id.toString()),
            )
            postSideEffect(TopicSideEffect.ShowDownloadProgress)
            reduce { state.copy(downloadState = DownloadState.Started) }
            val uri = downloadTorrentUseCase(id, title)
            if (uri != null) {
                intent { reduce { state.copy(downloadState = DownloadState.Completed(uri)) } }
            } else {
                analytics.event(
                    AnalyticsTracker.Events.DOWNLOAD_TORRENT_FAILURE,
                    mapOf(
                        AnalyticsTracker.Params.TOPIC_ID to id.toString(),
                        AnalyticsTracker.Params.ERROR to "download_failed",
                    ),
                )
                intent { reduce { state.copy(downloadState = DownloadState.Error) } }
            }
        } else {
            intent { postSideEffect(TopicSideEffect.ShowLoginRequired) }
        }
    }

    /**
     * LVA-052 — HTTP-file download path for HTTP_DOWNLOAD providers. These
     * providers are anonymous (AuthType.NONE), so this path does NOT gate on
     * [isAuthorizedUseCase] — requiring login would make the download
     * unreachable for archiveorg / gutenberg (the §6.G "auth-type honesty"
     * lesson). The real bytes reach disk via [downloadHttpFileUseCase].
     */
    private fun downloadHttpFile() = intent {
        analytics.event(
            AnalyticsTracker.Events.DOWNLOAD_TORRENT,
            mapOf(AnalyticsTracker.Params.TOPIC_ID to id.toString()),
        )
        postSideEffect(TopicSideEffect.ShowDownloadProgress)
        reduce { state.copy(downloadState = DownloadState.Started) }
        val uri = downloadHttpFileUseCase(providerId, id)
        if (uri != null) {
            intent { reduce { state.copy(downloadState = DownloadState.Completed(uri)) } }
        } else {
            analytics.event(
                AnalyticsTracker.Events.DOWNLOAD_TORRENT_FAILURE,
                mapOf(
                    AnalyticsTracker.Params.TOPIC_ID to id.toString(),
                    AnalyticsTracker.Params.ERROR to "http_download_failed",
                ),
            )
            intent { reduce { state.copy(downloadState = DownloadState.Error) } }
        }
    }

    private fun onOpenFileClick(uri: String) = intent {
        postSideEffect(TopicSideEffect.OpenFile(uri))
    }

    private fun createShareLink(): String {
        return "https://rutracker.org/forum/viewtopic.php?t=$id"
    }
}
