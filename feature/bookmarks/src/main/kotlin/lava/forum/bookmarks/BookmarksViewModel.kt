package lava.forum.bookmarks

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import lava.domain.usecase.ObserveBookmarksUseCase
import lava.domain.usecase.SyncBookmarksUseCase
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
    private val syncBookmarksUseCase: SyncBookmarksUseCase,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<BookmarksState, BookmarksSideEffect> {
    private val logger = loggerFactory.get("BookmarksViewModel")

    override val container: Container<BookmarksState, BookmarksSideEffect> = container(
        initialState = BookmarksState.Initial(),
        onCreate = { observeBookmarks() },
    )

    fun perform(action: BookmarksAction) {
        logger.d { "Perform $action" }
        when (action) {
            is BookmarksAction.BookmarkClicked -> intent {
                postSideEffect(BookmarksSideEffect.OpenCategory(action.bookmark.category.id))
            }
            is BookmarksAction.SyncNowClick -> onSyncNow()
        }
    }

    private fun observeBookmarks() = intent {
        logger.d { "Start observing bookmarks" }
        observeBookmarksUseCase().collectLatest { items ->
            val syncing = state.isSyncing
            reduce {
                logger.d { "On new bookmarks list: $items" }
                if (items.isEmpty()) {
                    BookmarksState.Empty(isSyncing = syncing)
                } else {
                    BookmarksState.BookmarksList(items, isSyncing = syncing)
                }
            }
        }
    }

    private fun onSyncNow() = intent {
        reduce {
            val s = state
            when (s) {
                is BookmarksState.Initial -> s.copy(isSyncing = true)
                is BookmarksState.Empty -> s.copy(isSyncing = true)
                is BookmarksState.BookmarksList -> s.copy(isSyncing = true)
            }
        }
        try {
            syncBookmarksUseCase()
        } catch (e: Exception) {
            logger.e(e) { "Sync now failed" }
        }
        reduce {
            val s = state
            when (s) {
                is BookmarksState.Initial -> s.copy(isSyncing = false)
                is BookmarksState.Empty -> s.copy(isSyncing = false)
                is BookmarksState.BookmarksList -> s.copy(isSyncing = false)
            }
        }
    }
}
