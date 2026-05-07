package lava.favorites

import lava.models.topic.Topic
import lava.models.topic.TopicModel

sealed interface FavoritesAction {
    data class TopicClick(val topicModel: TopicModel<out Topic>) : FavoritesAction
    data object SyncNowClick : FavoritesAction
}
