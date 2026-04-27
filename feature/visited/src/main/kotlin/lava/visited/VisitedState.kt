package lava.visited

import lava.models.topic.Topic
import lava.models.topic.TopicModel

internal sealed interface VisitedState {
    data object Initial : VisitedState
    data object Empty : VisitedState
    data class VisitedList(val items: List<TopicModel<out Topic>>) : VisitedState
}
