package lava.favorites

import lava.models.topic.Topic
import lava.models.topic.TopicModel

sealed interface FavoritesState {
    val isSyncing: Boolean

    data class Initial(override val isSyncing: Boolean = false) : FavoritesState
    data class Empty(override val isSyncing: Boolean = false) : FavoritesState
    data class FavoritesList(
        val items: List<TopicModel<out Topic>>,
        override val isSyncing: Boolean = false,
    ) : FavoritesState
}
