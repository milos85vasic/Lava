package lava.domain.usecase

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import lava.data.api.repository.BookmarksRepository
import lava.data.api.service.ForumService
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class UpdateBookmarkUseCase @Inject constructor(
    private val forumService: ForumService,
    private val bookmarksRepository: BookmarksRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String) {
        withContext(dispatchers.default) {
            runCatching {
                coroutineScope {
                    val category = forumService.getCategoryPage(id, 1)
                    bookmarksRepository.update(
                        id = id,
                        topics = category.items.topicsIds(),
                        newTopics = emptyList(),
                    )
                }
            }
        }
    }
}
