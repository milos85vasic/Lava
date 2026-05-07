package lava.forum.bookmarks

import lava.models.forum.CategoryModel

internal sealed interface BookmarksState {
    val isSyncing: Boolean

    data class Initial(override val isSyncing: Boolean = false) : BookmarksState
    data class Empty(override val isSyncing: Boolean = false) : BookmarksState
    data class BookmarksList(
        val items: List<CategoryModel>,
        override val isSyncing: Boolean = false,
    ) : BookmarksState
}
