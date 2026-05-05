package lava.topic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import lava.common.analytics.AnalyticsTracker
import lava.domain.model.PagingAction
import lava.domain.model.refresh
import lava.domain.model.retry
import lava.domain.usecase.AddCommentUseCase
import lava.domain.usecase.DownloadTorrentUseCase
import lava.domain.usecase.GetTopicUseCase
import lava.domain.usecase.IsAuthorizedUseCase
import lava.domain.usecase.ObserveFavoriteStateUseCase
import lava.domain.usecase.ObserveTopicPagingDataUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.logger.api.LoggerFactory
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.topic.Author
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
        runCatching { coroutineScope { getTopicUseCase(id) } }
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
        runCatching { toggleFavoriteUseCase(id) }
            .onFailure { postSideEffect(TopicSideEffect.ShowFavoriteToggleError) }
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

    private fun onOpenFileClick(uri: String) = intent {
        postSideEffect(TopicSideEffect.OpenFile(uri))
    }

    private fun createShareLink(): String {
        return "https://rutracker.org/forum/viewtopic.php?t=$id"
    }
}
