package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.ForumRepository
import lava.data.api.service.ForumService
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class RefreshForumUseCase @Inject constructor(
    private val forumRepository: ForumRepository,
    private val forumService: ForumService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            forumRepository.storeForum(forumService.getForum())
        }
    }
}
