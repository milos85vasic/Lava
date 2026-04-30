package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.model.CommentsPage
import lava.tracker.rutracker.domain.AddCommentUseCase
import lava.tracker.rutracker.domain.GetCommentsPageUseCase
import lava.tracker.rutracker.mapper.CommentsMapper
import javax.inject.Inject

/**
 * RuTracker implementation of [CommentsTracker]. Both UseCases require a
 * valid auth token (rutracker gates comment HTML and posting behind login).
 */
class RuTrackerComments @Inject constructor(
    private val getCommentsPage: GetCommentsPageUseCase,
    private val addCommentUseCase: AddCommentUseCase,
    private val mapper: CommentsMapper,
    private val tokenProvider: TokenProvider,
) : CommentsTracker {

    override suspend fun getComments(topicId: String, page: Int): CommentsPage {
        val token = tokenProvider.getToken()
        val dto = getCommentsPage(token, topicId, page)
        return mapper.toCommentsPage(dto, currentPage = page)
    }

    override suspend fun addComment(topicId: String, message: String): Boolean {
        val token = tokenProvider.getToken()
        return addCommentUseCase(token, topicId, message)
    }
}
