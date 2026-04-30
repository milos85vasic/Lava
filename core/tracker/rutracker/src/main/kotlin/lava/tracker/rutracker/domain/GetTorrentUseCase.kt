package lava.tracker.rutracker.domain

import lava.tracker.rutracker.api.RuTrackerInnerApi
import lava.network.dto.topic.TorrentDto
import lava.tracker.rutracker.model.Forbidden
import lava.tracker.rutracker.model.NotFound

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
