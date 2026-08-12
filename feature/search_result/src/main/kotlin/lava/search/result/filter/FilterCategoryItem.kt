package lava.search.result.filter

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import lava.designsystem.component.BodyLarge
import lava.designsystem.component.Icon
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.models.forum.Category
import lava.search.result.R
import lava.search.result.categories.CategorySelectionDialog
import lava.ui.component.rememberVisibilityState

@Composable
internal fun FilterCategoryItem(
    available: List<Category>,
    selected: List<Category>?,
    onSelect: (List<Category>?) -> Unit,
) {
    val dialogState = rememberVisibilityState()
    CategorySelectionDialog(
        state = dialogState,
        available = available,
        selected = selected,
        onSubmit = { categories ->
            onSelect(categories)
            dialogState.hide()
        },
        onDismiss = dialogState::hide,
    )
    FilterBarItem(label = stringResource(R.string.search_screen_filter_category_label)) {
        // §6.AK follow-up (2026-08-11): Row/Column don't add their own
        // semantics node, so FilterBar's rows flatten into one accessibility
        // level — a text+click selector for "Any" alone is ambiguous with
        // FilterAuthorItem's identically-worded default value. Tag this
        // surface explicitly so Challenge Tests (and any future test) can
        // target the category filter unambiguously.
        FilterBarItemContent(
            modifier = Modifier.testTag(FILTER_CATEGORY_VALUE_TEST_TAG),
            onClick = dialogState::show,
        ) {
            BodyLarge(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppTheme.spaces.large),
                text = when {
                    selected.isNullOrEmpty() -> stringResource(R.string.search_screen_filter_any)
                    selected.size == 1 -> selected.first().name
                    else -> stringResource(
                        R.string.search_screen_filter_category_counter,
                        selected.size,
                    )
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                modifier = Modifier.padding(AppTheme.spaces.medium),
                icon = LavaIcons.Forum,
                contentDescription = null,
            )
        }
    }
}

/**
 * Compose UI test tag for the category filter's clickable value surface
 * (renders "Any" / a category name / an N-selected counter). Not a §6.R
 * connection/credential literal — a UI test identifier, same class as the
 * content-description strings ("Search", "Expand filters", …) Challenge
 * Tests already target directly.
 */
const val FILTER_CATEGORY_VALUE_TEST_TAG = "filter_category_value"
