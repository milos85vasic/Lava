package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.BookmarksRepository
import lava.dispatchers.api.Dispatchers
import lava.work.api.BackgroundService
import javax.inject.Inject

class ToggleBookmarkUseCase @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    private val backgroundService: BackgroundService,
    private val getCategoryUseCase: GetCategoryUseCase,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(categoryId: String) {
        withContext(dispatchers.default) {
            if (bookmarksRepository.isBookmark(categoryId)) {
                bookmarksRepository.remove(categoryId)
            } else {
                bookmarksRepository.add(getCategoryUseCase(categoryId))
                backgroundService.updateBookmark(categoryId)
            }
        }
    }
}
