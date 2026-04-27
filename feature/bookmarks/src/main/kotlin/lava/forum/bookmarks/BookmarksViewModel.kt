package lava.forum.bookmarks

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import lava.domain.usecase.ObserveBookmarksUseCase
import lava.logger.api.LoggerFactory
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class BookmarksViewModel @Inject constructor(
    private val observeBookmarksUseCase: ObserveBookmarksUseCase,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<BookmarksState, BookmarksSideEffect> {
    private val logger = loggerFactory.get("BookmarksViewModel")

    override val container: Container<BookmarksState, BookmarksSideEffect> = container(
        initialState = BookmarksState.Initial,
        onCreate = { observeBookmarks() },
    )

    fun perform(action: BookmarksAction) {
        logger.d { "Perform $action" }
        when (action) {
            is BookmarksAction.BookmarkClicked -> intent {
                postSideEffect(BookmarksSideEffect.OpenCategory(action.bookmark.category.id))
            }
        }
    }

    private fun observeBookmarks() = intent {
        logger.d { "Start observing bookmarks" }
        observeBookmarksUseCase().collectLatest { items ->
            reduce {
                logger.d { "On new bookmarks list: $items" }
                if (items.isEmpty()) {
                    BookmarksState.Empty
                } else {
                    BookmarksState.BookmarksList(items)
                }
            }
        }
    }
}
