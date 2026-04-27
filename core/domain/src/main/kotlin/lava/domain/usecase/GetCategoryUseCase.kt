package lava.domain.usecase

import lava.data.api.repository.ForumRepository
import lava.dispatchers.api.Dispatchers
import lava.models.forum.Category
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetCategoryUseCase @Inject constructor(
    private val ensureForumLoadUseCase: EnsureForumLoadUseCase,
    private val refreshForumUseCase: RefreshForumUseCase,
    private val forumRepository: ForumRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String): Category {
        return withContext(dispatchers.default) {
            ensureForumLoadUseCase()
            val category = forumRepository.getCategory(id)
            if (category == null) {
                refreshForumUseCase.invoke()
            }
            requireNotNull(forumRepository.getCategory(id))
        }
    }
}
