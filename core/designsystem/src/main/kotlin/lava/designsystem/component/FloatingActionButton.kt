package lava.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import lava.designsystem.R
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme

@Composable
fun ScrollBackFloatingActionButton(
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.designsystem_content_description_action_scroll_to_top),
) {
    val scrollState = LocalScrollState.current
    AnimatedVisibility(
        visible = scrollState.canScrollUp,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            modifier = modifier.size(AppTheme.sizes.default),
            onClick = scrollState::scrollUp,
            color = AppTheme.colors.primaryContainer,
            shape = AppTheme.shapes.large,
            shadowElevation = AppTheme.elevations.medium,
            content = {
                Icon(
                    modifier = Modifier.padding(AppTheme.spaces.medium),
                    icon = LavaIcons.ScrollToTop,
                    contentDescription = contentDescription,
                )
            },
        )
    }
}

@Composable
@NonRestartableComposable
fun AddCommentFloatingActionButton(
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.designsystem_content_description_action_add_comment),
    onClick: () -> Unit,
) = Surface(
    modifier = modifier.size(AppTheme.sizes.default),
    onClick = onClick,
    color = AppTheme.colors.primaryContainer,
    shape = AppTheme.shapes.large,
    shadowElevation = AppTheme.elevations.medium,
    content = {
        Icon(
            modifier = Modifier.padding(AppTheme.spaces.medium),
            icon = LavaIcons.Comment,
            contentDescription = contentDescription,
        )
    },
)

@ThemePreviews
@Composable
private fun ScrollBackFloatingActionButtonPreview() {
    AddCommentFloatingActionButton {}
}
