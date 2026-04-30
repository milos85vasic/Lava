package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.mapper.TopicMapper
import javax.inject.Inject

/**
 * RuTracker implementation of [TopicTracker]. Both UseCases require a token
 * (rutracker gates topic detail HTML behind login). Page numbers are passed
 * through as-is — pagination semantics live in TopicMapper (Task 2.17).
 */
class RuTrackerTopic @Inject constructor(
    private val getTopic: GetTopicUseCase,
    private val getTopicPage: GetTopicPageUseCase,
    private val mapper: TopicMapper,
    private val tokenProvider: TokenProvider,
) : TopicTracker {

    override suspend fun getTopic(id: String): TopicDetail {
        val token = tokenProvider.getToken()
        val dto = getTopic(token, id, page = null)
        return mapper.toTopicDetail(dto)
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage {
        val token = tokenProvider.getToken()
        val dto = getTopicPage(token, id, page)
        return mapper.toTopicPage(dto, currentPage = page)
    }
}
