package lava.search.result.categories

import lava.search.result.domain.models.ForumTreeItem

internal sealed interface CategorySelectionState {
    data object Loading : CategorySelectionState
    data class Success(val items: List<ForumTreeItem>) : CategorySelectionState
    data class Error(val exception: Throwable? = null) : CategorySelectionState
}
