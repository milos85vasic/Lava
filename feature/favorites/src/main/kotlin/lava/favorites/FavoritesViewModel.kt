package lava.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import lava.common.analytics.AnalyticsTracker
import lava.domain.usecase.ObserveFavoritesUseCase
import lava.domain.usecase.RefreshFavoritesUseCase
import lava.logger.api.LoggerFactory
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val observeFavoritesUseCase: ObserveFavoritesUseCase,
    private val refreshFavoritesUseCase: RefreshFavoritesUseCase,
    loggerFactory: LoggerFactory,
    private val analytics: AnalyticsTracker,
) : ViewModel(), ContainerHost<FavoritesState, FavoritesSideEffect> {
    private val logger = loggerFactory.get("FavoritesViewModel")

    override val container: Container<FavoritesState, FavoritesSideEffect> = container(
        initialState = FavoritesState.Initial(),
        onCreate = { observeFavorites() },
    )

    fun perform(action: FavoritesAction) {
        logger.d { "Perform $action" }
        when (action) {
            is FavoritesAction.TopicClick -> onTopicClick(action.topicModel)
            is FavoritesAction.SyncNowClick -> onSyncNow()
        }
    }

    private fun observeFavorites() = intent {
        logger.d { "Start observing favorites" }
        observeFavoritesUseCase(viewModelScope).collectLatest { items ->
            logger.d { "On new favorites list: $items" }
            val syncing = state.isSyncing
            reduce {
                if (items.isEmpty()) {
                    FavoritesState.Empty(isSyncing = syncing)
                } else {
                    FavoritesState.FavoritesList(items, isSyncing = syncing)
                }
            }
        }
    }

    private fun onTopicClick(topicModel: TopicModel<out Topic>) = intent {
        postSideEffect(FavoritesSideEffect.OpenTopic(topicModel.topic.id))
    }

    private fun onSyncNow() = intent {
        reduce {
            val s = state
            when (s) {
                is FavoritesState.Initial -> s.copy(isSyncing = true)
                is FavoritesState.Empty -> s.copy(isSyncing = true)
                is FavoritesState.FavoritesList -> s.copy(isSyncing = true)
            }
        }
        try {
            refreshFavoritesUseCase()
        } catch (e: Exception) {
            analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.ERROR to "sync_favorites_failed"))
            logger.e(e) { "Sync now failed" }
        }
        reduce {
            val s = state
            when (s) {
                is FavoritesState.Initial -> s.copy(isSyncing = false)
                is FavoritesState.Empty -> s.copy(isSyncing = false)
                is FavoritesState.FavoritesList -> s.copy(isSyncing = false)
            }
        }
    }
}
