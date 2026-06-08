package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.magnet.RuTrackerMagnetCache
import lava.tracker.rutracker.mapper.TopicMapper
import javax.inject.Inject

/**
 * RuTracker implementation of [TopicTracker]. Both UseCases require a token
 * (rutracker gates topic detail HTML behind login). Page numbers are passed
 * through as-is — pagination semantics live in TopicMapper (Task 2.17).
 *
 * §6.E: every successful topic fetch records the genuinely-parsed magnet into
 * the shared [RuTrackerMagnetCache] so the synchronous
 * [lava.tracker.api.feature.DownloadableTracker.getMagnetLink] can surface it
 * (mirrors RuTor; see [RuTrackerMagnetCache] + GetMagnetLinkUseCase).
 */
class RuTrackerTopic @Inject constructor(
    private val getTopic: GetTopicUseCase,
    private val getTopicPage: GetTopicPageUseCase,
    private val mapper: TopicMapper,
    private val tokenProvider: TokenProvider,
    private val magnetCache: RuTrackerMagnetCache,
) : TopicTracker {

    override suspend fun getTopic(id: String): TopicDetail {
        val token = tokenProvider.getToken()
        val dto = getTopic(token, id, page = null)
        val detail = mapper.toTopicDetail(dto)
        magnetCache.put(id, detail.torrent.magnetUri)
        return detail
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage {
        val token = tokenProvider.getToken()
        val dto = getTopicPage(token, id, page)
        return mapper.toTopicPage(dto, currentPage = page)
    }
}
