package lava.forum.bookmarks

import lava.models.forum.CategoryModel

internal sealed interface BookmarksAction {
    data class BookmarkClicked(val bookmark: CategoryModel) : BookmarksAction
}
