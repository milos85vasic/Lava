package lava.network.domain

import lava.network.api.RuTrackerInnerApi
import lava.network.dto.topic.TorrentDto
import lava.network.model.Forbidden
import lava.network.model.NotFound

internal class GetTorrentUseCase(
    private val api: RuTrackerInnerApi,
    private val parseTorrentUseCase: ParseTorrentUseCase,
) {
    suspend operator fun invoke(token: String, id: String): TorrentDto {
        val html = api.topic(token, id)
        return when {
            !isTopicExists(html) -> throw NotFound
            isTopicModerated(html) -> throw Forbidden
            isBlockedForRegion(html) -> throw Forbidden
            else -> parseTorrentUseCase(html)
        }
    }
}
