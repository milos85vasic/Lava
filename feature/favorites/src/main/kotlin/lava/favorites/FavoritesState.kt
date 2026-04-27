package lava.favorites

import lava.models.topic.Topic
import lava.models.topic.TopicModel

sealed interface FavoritesState {
    data object Initial : FavoritesState
    data object Empty : FavoritesState
    data class FavoritesList(val items: List<TopicModel<out Topic>>) : FavoritesState
}
