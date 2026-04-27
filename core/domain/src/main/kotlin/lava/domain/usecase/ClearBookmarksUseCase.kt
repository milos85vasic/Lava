package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.BookmarksRepository
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class ClearBookmarksUseCase @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            bookmarksRepository.clear()
        }
    }
}
