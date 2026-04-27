package lava.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import lava.designsystem.component.Surface
import lava.designsystem.component.ThemePreviews
import lava.designsystem.theme.AppTheme
import lava.designsystem.theme.LavaTheme
import lava.ui.R

@Composable
fun Avatar(url: String?) = RemoteImage(
    src = url,
    contentDescription = null,
    onLoading = { AvatarPlaceholder() },
    onSuccess = { painter ->
        Image(
            modifier = Modifier
                .size(44.dp)
                .clip(AppTheme.shapes.circle),
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
    },
    onError = { AvatarPlaceholder() },
)

@Composable
private fun AvatarPlaceholder() {
    Image(
        modifier = Modifier
            .size(AppTheme.sizes.default)
            .clip(AppTheme.shapes.circle),
        painter = painterResource(
            if (AppTheme.colors.isDark) {
                R.drawable.ic_avatar_dark
            } else {
                R.drawable.ic_avatar_light
            },
        ),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color = AppTheme.colors.onPrimaryContainer),
    )
}

@ThemePreviews
@Composable
private fun Avatar_Preview() {
    LavaTheme { Surface { Avatar(null) } }
}
