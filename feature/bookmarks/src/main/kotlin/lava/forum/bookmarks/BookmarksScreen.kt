package lava.forum.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lava.designsystem.component.BodyLarge
import lava.designsystem.component.CircularProgressIndicator
import lava.designsystem.component.Icon
import lava.designsystem.component.IconButton
import lava.designsystem.component.LazyList
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.component.ThemePreviews
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.designsystem.theme.LavaTheme
import lava.models.forum.Category
import lava.models.forum.CategoryModel
import lava.navigation.viewModel
import lava.ui.component.emptyItem
import lava.ui.component.loadingItem
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun BookmarksScreen(
    openCategory: (String) -> Unit,
) = BookmarksScreen(
    viewModel = viewModel(),
    openCategory = openCategory,
)

@Composable
private fun BookmarksScreen(
    viewModel: BookmarksViewModel,
    openCategory: (String) -> Unit,
) {
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is BookmarksSideEffect.OpenCategory -> openCategory(sideEffect.categoryId)
        }
    }
    val state by viewModel.collectAsState()
    BookmarksScreen(state, viewModel::perform)
}

@Composable
private fun BookmarksScreen(
    state: BookmarksState,
    onAction: (BookmarksAction) -> Unit,
) = Box(modifier = Modifier.fillMaxSize()) {
    LazyList(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = AppTheme.spaces.large),
    ) {
        when (state) {
            is BookmarksState.Initial -> loadingItem()
            is BookmarksState.Empty -> emptyItem(
                titleRes = R.string.forum_screen_bookmarks_empty_title,
                subtitleRes = R.string.forum_screen_bookmarks_empty_subtitle,
                imageRes = R.drawable.ill_bookmarks,
            )

            is BookmarksState.BookmarksList -> items(items = state.items) { bookmark ->
                Bookmark(
                    bookmark = bookmark,
                    onClick = { onAction(BookmarksAction.BookmarkClicked(bookmark)) },
                )
            }
        }
    }
    if (state.isSyncing) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AppTheme.spaces.medium)
                .size(24.dp),
        )
    } else {
        IconButton(
            icon = LavaIcons.Reload,
            contentDescription = "Sync now",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AppTheme.spaces.medium),
            onClick = { onAction(BookmarksAction.SyncNowClick) },
        )
    }
}

@Composable
private fun Bookmark(
    bookmark: CategoryModel,
    onClick: () -> Unit,
) = Surface(
    modifier = Modifier.padding(
        horizontal = AppTheme.spaces.mediumLarge,
        vertical = AppTheme.spaces.mediumSmall,
    ),
    onClick = onClick,
    shape = AppTheme.shapes.large,
    tonalElevation = AppTheme.elevations.small,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large)
            .defaultMinSize(minHeight = AppTheme.sizes.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon = LavaIcons.BookmarkChecked,
            tint = AppTheme.colors.primary,
            contentDescription = null,
        )
        BodyLarge(
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = AppTheme.spaces.medium,
                    vertical = AppTheme.spaces.large,
                ),
            text = bookmark.category.name,
        )
        if (bookmark.newTopicsCount > 0) {
            Text(
                modifier = Modifier
                    .background(
                        color = AppTheme.colors.primary,
                        shape = AppTheme.shapes.circle,
                    )
                    .padding(
                        horizontal = AppTheme.spaces.mediumLarge,
                        vertical = AppTheme.spaces.medium,
                    ),
                text = bookmark.newTopicsCount.toString(),
                color = AppTheme.colors.onPrimary,
                style = AppTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@ThemePreviews
@Composable
fun Bookmark_Preview() {
    LavaTheme {
        Bookmark(
            bookmark = CategoryModel(
                category = Category("id", "Category name"),
                newTopicsCount = 3,
            ),
            onClick = {},
        )
    }
}
