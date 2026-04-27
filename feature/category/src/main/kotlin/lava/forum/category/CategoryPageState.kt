package lava.forum.category

import lava.domain.model.LoadStates
import lava.models.auth.AuthState
import lava.models.topic.TopicModel

internal data class CategoryPageState(
    val authState: AuthState = AuthState.Unauthorized,
    val categoryState: CategoryState = CategoryState.Initial,
    val categoryContent: CategoryContent = CategoryContent.Initial,
    val loadStates: LoadStates = LoadStates.Idle,
)

internal sealed interface CategoryState {
    data object Initial : CategoryState
    data class Category(
        val name: String,
        val isBookmark: Boolean,
    ) : CategoryState
}

internal sealed interface CategoryContent {
    data object Initial : CategoryContent
    data object Empty : CategoryContent
    data class Content(val items: List<CategoryItem>) : CategoryContent
}

internal sealed interface CategoryItem {
    data class SectionHeader(val name: String) : CategoryItem
    data class Category(val category: lava.models.forum.Category) : CategoryItem
    data class Topic(val topic: TopicModel<out lava.models.topic.Topic>) : CategoryItem
}
