package lava.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.designsystem.component.CircularProgressIndicator
import lava.designsystem.component.Empty
import lava.designsystem.component.Icon
import lava.designsystem.component.IconButton
import lava.designsystem.component.LazyList
import lava.designsystem.component.Loading
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.navigation.viewModel
import lava.ui.component.TopicListItem
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun FavoritesScreen(
    openTopic: (id: String) -> Unit,
) = FavoritesScreen(
    viewModel = viewModel(),
    openTopic = openTopic,
)

@Composable
private fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    openTopic: (id: String) -> Unit,
) {
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is FavoritesSideEffect.OpenTopic -> openTopic(sideEffect.id)
        }
    }
    val state by viewModel.collectAsState()
    FavoritesScreen(state, viewModel::perform)
}

@Composable
private fun FavoritesScreen(
    state: FavoritesState,
    onAction: (FavoritesAction) -> Unit,
) = Box(modifier = Modifier.fillMaxSize()) {
    when (state) {
        is FavoritesState.Initial -> Loading()
        is FavoritesState.Empty -> Empty(
            titleRes = R.string.favorites_empty_title,
            subtitleRes = R.string.favorites_empty_subtitle,
            imageRes = R.drawable.ill_favorites,
        )
        is FavoritesState.FavoritesList -> LazyList(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = AppTheme.spaces.medium),
        ) {
            items(items = state.items) { model ->
                FavoriteTopic(
                    topicModel = model,
                    onClick = { onAction(FavoritesAction.TopicClick(model)) },
                )
            }
        }
    }
    if (state.isSyncing) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AppTheme.spaces.medium)
                .size(24.dp),
        )
    } else {
        IconButton(
            icon = LavaIcons.Reload,
            contentDescription = "Sync now",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AppTheme.spaces.medium),
            onClick = { onAction(FavoritesAction.SyncNowClick) },
        )
    }
}

@Composable
private fun FavoriteTopic(
    topicModel: TopicModel<out Topic>,
    onClick: () -> Unit,
) = TopicListItem(
    modifier = Modifier.padding(
        horizontal = AppTheme.spaces.mediumLarge,
        vertical = AppTheme.spaces.mediumSmall,
    ),
    topic = topicModel.topic,
    onClick = onClick,
    action = {
        if (topicModel.hasUpdate) {
            Icon(
                icon = LavaIcons.NewBadge,
                tint = AppTheme.colors.primary,
                contentDescription = null,
            )
        }
    },
)
