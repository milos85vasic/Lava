package lava.designsystem.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import lava.models.settings.Theme

@Composable
fun LavaTheme(
    theme: Theme = Theme.SYSTEM,
    isDark: Boolean = isSystemInDarkTheme(),
    isDynamic: Boolean = isMaterialYouAvailable(),
    content: @Composable () -> Unit,
) {
    val colors = when (theme) {
        Theme.DYNAMIC -> if (isDynamic && isMaterialYouAvailable()) {
            dynamicColors(LocalContext.current, isDark)
        } else {
            yoleColors(isDark)
        }
        Theme.DARK -> yoleColors(isDark = true)
        Theme.LIGHT -> yoleColors(isDark = false)
        Theme.YOLE, Theme.SYSTEM -> yoleColors(isDark)
        Theme.DRACULA -> draculaColors(isDark)
        Theme.SOLARIZED -> solarizedColors(isDark)
        Theme.NORD -> nordColors(isDark)
        Theme.MONOKAI -> monokaiColors(isDark)
        Theme.GRUVBOX -> gruvboxColors(isDark)
        Theme.ONEDARK -> oneDarkColors(isDark)
        Theme.TOKYONIGHT -> tokyoNightColors(isDark)
    }
    val materialColorScheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary, onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer, onSecondaryContainer = colors.onSecondaryContainer,
            tertiary = colors.tertiary, onTertiary = colors.onTertiary,
            tertiaryContainer = colors.tertiaryContainer, onTertiaryContainer = colors.onTertiaryContainer,
            background = colors.background, onBackground = colors.onBackground,
            surface = colors.surface, onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant, onSurfaceVariant = colors.onSurfaceVariant,
            error = colors.error, onError = colors.onError,
            errorContainer = colors.errorContainer, onErrorContainer = colors.onErrorContainer,
            outline = colors.outline, outlineVariant = colors.outlineVariant,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary, onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer, onSecondaryContainer = colors.onSecondaryContainer,
            tertiary = colors.tertiary, onTertiary = colors.onTertiary,
            tertiaryContainer = colors.tertiaryContainer, onTertiaryContainer = colors.onTertiaryContainer,
            background = colors.background, onBackground = colors.onBackground,
            surface = colors.surface, onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant, onSurfaceVariant = colors.onSurfaceVariant,
            error = colors.error, onError = colors.onError,
            errorContainer = colors.errorContainer, onErrorContainer = colors.onErrorContainer,
            outline = colors.outline, outlineVariant = colors.outlineVariant,
        )
    }
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = materialColorScheme, content = content)
    }
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun isMaterialYouAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
