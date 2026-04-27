package lava.visited

import lava.models.topic.Topic
import lava.models.topic.TopicModel

internal sealed interface VisitedAction {
    data class TopicClick(val topicModel: TopicModel<out Topic>) : VisitedAction
    data class FavoriteClick(val topicModel: TopicModel<out Topic>) : VisitedAction
}
