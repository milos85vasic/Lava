package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage

interface TopicTracker : TrackerFeature {
    suspend fun getTopic(id: String): TopicDetail

    suspend fun getTopicPage(id: String, page: Int): TopicPage
}
