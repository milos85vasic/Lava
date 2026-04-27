package lava.network.domain

import lava.network.api.RuTrackerInnerApi
import lava.network.dto.topic.CommentsPageDto
import lava.network.model.Forbidden
import lava.network.model.NotFound

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
