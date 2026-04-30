package lava.tracker.rutracker.mapper

import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicPageDto
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import javax.inject.Inject

/**
 * Maps the legacy [ForumTopicDto] / [TopicPageDto] (rutracker topic + page
 * scrapes) to the new tracker-api [TopicDetail] / [TopicPage]. Stub here;
 * populated in Task 2.17.
 *
 * The DTO returned by GetTopicUseCase is a sealed [ForumTopicDto] (Topic /
 * Torrent / CommentsPage); the mapper handles all branches.
 */
class TopicMapper @Inject constructor() {
    fun toTopicDetail(dto: ForumTopicDto): TopicDetail {
        TODO("populated in Task 2.17")
    }

    fun toTopicPage(dto: TopicPageDto): TopicPage {
        TODO("populated in Task 2.17")
    }
}
