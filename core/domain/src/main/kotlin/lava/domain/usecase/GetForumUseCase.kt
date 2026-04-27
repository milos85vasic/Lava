package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.ForumRepository
import lava.dispatchers.api.Dispatchers
import lava.models.forum.Forum
import javax.inject.Inject

class GetForumUseCase @Inject constructor(
    private val ensureForumLoadUseCase: EnsureForumLoadUseCase,
    private val forumRepository: ForumRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(): Forum {
        return withContext(dispatchers.default) {
            ensureForumLoadUseCase()
            forumRepository.getForum()
        }
    }
}
