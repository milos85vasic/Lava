package lava.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import lava.common.mapInstanceOf
import lava.data.api.service.ForumService
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.model.PagingDataLoader
import lava.domain.model.category.CategoryPage
import lava.domain.model.refresh
import lava.logger.api.LoggerFactory
import lava.models.forum.ForumItem
import javax.inject.Inject

class ObserveCategoryPagingDataUseCase @Inject constructor(
    private val forumService: ForumService,
    private val enrichTopicsUseCase: EnrichTopicsUseCase,
    private val visitCategoryUseCase: VisitCategoryUseCase,
    private val loggerFactory: LoggerFactory,
) {
    suspend operator fun invoke(
        id: String,
        actionsFlow: Flow<PagingAction>,
        scope: CoroutineScope,
    ): Flow<PagingData<CategoryPage>> {
        return PagingDataLoader(
            fetchData = { page ->
                forumService.getCategoryPage(id, page).also { categoryPage ->
                    if (categoryPage.page == 1) {
                        visitCategoryUseCase(id, categoryPage.items.topics())
                    }
                }
            },
            transform = { forumItems ->
                enrichTopicsUseCase(forumItems.topics()).map { topicModels ->
                    CategoryPage(
                        categories = forumItems.mapInstanceOf(ForumItem.Category::category),
                        sections = forumItems.mapInstanceOf(ForumItem.Section::section),
                        topics = topicModels,
                    )
                }
            },
            actions = actionsFlow.onStart { refresh() },
            scope = scope,
            logger = loggerFactory.get("CategoryPagingDataLoader"),
        ).flow
    }
}
