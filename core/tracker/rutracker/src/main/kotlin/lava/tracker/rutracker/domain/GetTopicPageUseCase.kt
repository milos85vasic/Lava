package lava.tracker.rutracker.domain

import lava.network.dto.topic.TopicPageDto
import lava.tracker.rutracker.api.RuTrackerInnerApi
import lava.tracker.rutracker.model.Forbidden
import lava.tracker.rutracker.model.NotFound

class GetTopicPageUseCase(
    private val api: RuTrackerInnerApi,
    private val parseTopicPageUseCase: ParseTopicPageUseCase,
) {
    suspend operator fun invoke(
        token: String,
        id: String,
        page: Int?,
    ): TopicPageDto {
        val html = api.topic(token, id, page)
        return when {
            !isTopicExists(html) -> throw NotFound
            isTopicModerated(html) -> throw Forbidden
            isBlockedForRegion(html) -> throw Forbidden
            else -> parseTopicPageUseCase(html)
        }
    }
}
