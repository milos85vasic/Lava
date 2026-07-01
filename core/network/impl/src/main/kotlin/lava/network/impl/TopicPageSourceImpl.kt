package lava.network.impl

import lava.network.api.TopicPageSource
import lava.network.dto.topic.TopicPageDto
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.rutracker.mapper.RuTrackerDtoMappers
import javax.inject.Inject

/**
 * LVA-070 — production [TopicPageSource], the `core:network:impl` adapter that
 * bridges `core:data` to the Tracker SDK's per-provider topic surface.
 *
 * Mirrors the [SwitchingNetworkApi] SDK path: delegates to
 * [LavaTrackerSdk.getTopicPage] (provider-aware overload) which resolves the
 * provider's [lava.tracker.api.feature.TopicTracker] (Capability Honesty,
 * clause 6.E — null when the provider doesn't declare TOPIC) and performs the
 * real fetch, then maps the SDK [lava.tracker.api.model.TopicPage] to the
 * wire-shape [TopicPageDto] via [RuTrackerDtoMappers.topicPageToDto] — the SAME
 * mapper `SwitchingNetworkApi.getTopicPage` uses, so the DTO (and the
 * downstream `TopicContent.Torrent` render that gates the topic download
 * affordance) is byte-identical to the direct-rutracker SDK path.
 *
 * Returns null when the SDK returns null (unknown provider / no TOPIC / fetch
 * threw) so the caller can fall back to the legacy proxy path.
 */
internal class TopicPageSourceImpl @Inject constructor(
    private val sdk: LavaTrackerSdk,
    private val mappers: RuTrackerDtoMappers,
) : TopicPageSource {

    override suspend fun getTopicPage(trackerId: String, id: String, page: Int): TopicPageDto? {
        val topicPage = sdk.getTopicPage(trackerId, id, page) ?: return null
        return mappers.topicPageToDto(topicPage)
    }
}
