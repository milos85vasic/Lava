package lava.domain.usecase

import lava.data.api.repository.BookmarksRepository
import lava.models.forum.CategoryModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

class ObserveBookmarksUseCase @Inject constructor(
    private val repository: BookmarksRepository,
) {
    operator fun invoke(): Flow<List<CategoryModel>> {
        return repository.observeBookmarks()
            .distinctUntilChanged()
            .catch {
                repository.clear()
                emit(emptyList())
            }
    }
}
