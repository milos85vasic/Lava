package lava.tracker.rutracker.domain

import lava.network.dto.topic.CommentsPageDto
import lava.tracker.rutracker.api.RuTrackerInnerApi
import lava.tracker.rutracker.model.Forbidden
import lava.tracker.rutracker.model.NotFound

internal class GetCommentsPageUseCase(
    private val api: RuTrackerInnerApi,
    private val parseCommentsPageUseCase: ParseCommentsPageUseCase,
) {

    suspend operator fun invoke(
        token: String,
        id: String,
        page: Int?,
    ): CommentsPageDto {
        val html = api.topic(token, id, page)
        return when {
            !isTopicExists(html) -> throw NotFound
            isTopicModerated(html) -> throw Forbidden
            isBlockedForRegion(html) -> throw Forbidden
            else -> parseCommentsPageUseCase(html)
        }
    }
}
