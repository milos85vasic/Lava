package lava.domain.usecase

import lava.data.api.repository.BookmarksRepository
import lava.models.forum.CategoryModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class ObserveCategoryModelUseCase @Inject constructor(
    private val getCategoryUseCase: GetCategoryUseCase,
    private val bookmarksRepository: BookmarksRepository,
) {
    suspend operator fun invoke(categoryId: String): Flow<CategoryModel> {
        val category = getCategoryUseCase(categoryId)
        return combine(
            bookmarksRepository.observeIds(),
            bookmarksRepository.observeNewTopics(categoryId),
        ) { bookmarks, newTopics ->
            CategoryModel(
                category = category,
                isBookmark = bookmarks.contains(categoryId),
                newTopicsCount = newTopics.size,
            )
        }
    }
}
