package lava.main

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import lava.designsystem.platform.PlatformType
import lava.designsystem.theme.LavaTheme
import lava.designsystem.utils.rememberSystemBarStyle
import lava.models.settings.Theme

@Composable
fun MainScreen(
    theme: Theme,
    platformType: PlatformType,
    content: @Composable () -> Unit,
) {
    LavaTheme(
        theme = theme,
        isDark = platformType == PlatformType.TV || theme.isDark(),
        isDynamic = theme.isDynamic(),
    ) {
        val activity = LocalContext.current as ComponentActivity
        val systemBarStyle = rememberSystemBarStyle()
        LaunchedEffect(systemBarStyle) {
            activity.enableEdgeToEdge(
                statusBarStyle = systemBarStyle,
                navigationBarStyle = systemBarStyle,
            )
        }
        content()
    }
}

@Composable
private fun Theme.isDark(): Boolean = when (this) {
    Theme.DARK -> true
    Theme.LIGHT -> false
    Theme.SYSTEM, Theme.DYNAMIC, Theme.YOLE, Theme.DRACULA, Theme.SOLARIZED,
    Theme.NORD, Theme.MONOKAI, Theme.GRUVBOX, Theme.ONEDARK, Theme.TOKYONIGHT,
    -> isSystemInDarkTheme()
}

private fun Theme.isDynamic(): Boolean = this == Theme.DYNAMIC
