package lava.forum.bookmarks

import lava.models.forum.CategoryModel

internal sealed interface BookmarksState {
    data object Initial : BookmarksState
    data object Empty : BookmarksState
    data class BookmarksList(val items: List<CategoryModel>) : BookmarksState
}
