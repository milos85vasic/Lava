package lava.search.result.categories

import lava.models.forum.Category

interface CategorySelectionSideEffect {
    data class OnSelect(val items: List<Category>) : CategorySelectionSideEffect
    data class OnRemove(val items: List<Category>) : CategorySelectionSideEffect
}
