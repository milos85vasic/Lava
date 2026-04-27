package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.ForumRepository
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class EnsureForumLoadUseCase @Inject constructor(
    private val refreshForumUseCase: RefreshForumUseCase,
    private val forumRepository: ForumRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            if (!forumRepository.isNotEmpty() || !forumRepository.isForumFresh()) {
                refreshForumUseCase.invoke()
            }
        }
    }
}
