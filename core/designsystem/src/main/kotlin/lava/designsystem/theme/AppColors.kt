package lava.designsystem.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import lava.designsystem.component.Label

@Suppress("LongParameterList")
@Stable
class AppColors internal constructor(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    onTertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    outline: Color,
    outlineVariant: Color,
    surface: Color,
    onSurface: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    background: Color,
    onBackground: Color,
    error: Color,
    onError: Color,
    errorContainer: Color,
    onErrorContainer: Color,
    accentGreen: Color,
    accentBlue: Color,
    accentOrange: Color,
    accentRed: Color,
    val isDark: Boolean,
) {
    var primary by mutableStateOf(primary, structuralEqualityPolicy())
        internal set
    var onPrimary by mutableStateOf(onPrimary, structuralEqualityPolicy())
        internal set
    var primaryContainer by mutableStateOf(primaryContainer, structuralEqualityPolicy())
        internal set
    var onPrimaryContainer by mutableStateOf(onPrimaryContainer, structuralEqualityPolicy())
        internal set
    var secondary by mutableStateOf(secondary, structuralEqualityPolicy())
        internal set
    var onSecondary by mutableStateOf(onSecondary, structuralEqualityPolicy())
        internal set
    var secondaryContainer by mutableStateOf(secondaryContainer, structuralEqualityPolicy())
        internal set
    var onSecondaryContainer by mutableStateOf(onSecondaryContainer, structuralEqualityPolicy())
        internal set
    var tertiary by mutableStateOf(tertiary, structuralEqualityPolicy())
        internal set
    var onTertiary by mutableStateOf(onTertiary, structuralEqualityPolicy())
        internal set
    var tertiaryContainer by mutableStateOf(tertiaryContainer, structuralEqualityPolicy())
        internal set
    var onTertiaryContainer by mutableStateOf(onTertiaryContainer, structuralEqualityPolicy())
        internal set
    var outline by mutableStateOf(outline, structuralEqualityPolicy())
        internal set
    var outlineVariant by mutableStateOf(outlineVariant, structuralEqualityPolicy())
        internal set
    var surface by mutableStateOf(surface, structuralEqualityPolicy())
        internal set
    var onSurface by mutableStateOf(onSurface, structuralEqualityPolicy())
        internal set
    var surfaceVariant by mutableStateOf(surfaceVariant, structuralEqualityPolicy())
        internal set
    var onSurfaceVariant by mutableStateOf(onSurfaceVariant, structuralEqualityPolicy())
        internal set
    var background by mutableStateOf(background, structuralEqualityPolicy())
        internal set
    var onBackground by mutableStateOf(onBackground, structuralEqualityPolicy())
        internal set
    var error by mutableStateOf(error, structuralEqualityPolicy())
        internal set
    var onError by mutableStateOf(onError, structuralEqualityPolicy())
        internal set
    var errorContainer by mutableStateOf(errorContainer, structuralEqualityPolicy())
        internal set
    var onErrorContainer by mutableStateOf(onErrorContainer, structuralEqualityPolicy())
        internal set
    var accentBlue by mutableStateOf(accentBlue, structuralEqualityPolicy())
        internal set
    var accentGreen by mutableStateOf(accentGreen, structuralEqualityPolicy())
        internal set
    var accentOrange by mutableStateOf(accentOrange, structuralEqualityPolicy())
        internal set
    var accentRed by mutableStateOf(accentRed, structuralEqualityPolicy())
        internal set

    @Suppress("LongParameterList")
    fun copy(
        primary: Color = this.primary,
        onPrimary: Color = this.onPrimary,
        primaryContainer: Color = this.primaryContainer,
        onPrimaryContainer: Color = this.onPrimaryContainer,
        secondary: Color = this.secondary,
        onSecondary: Color = this.onSecondary,
        secondaryContainer: Color = this.secondaryContainer,
        onSecondaryContainer: Color = this.onSecondaryContainer,
        tertiary: Color = this.tertiary,
        onTertiary: Color = this.onTertiary,
        tertiaryContainer: Color = this.tertiaryContainer,
        onTertiaryContainer: Color = this.onTertiaryContainer,
        outline: Color = this.outline,
        outlineVariant: Color = this.outlineVariant,
        background: Color = this.background,
        onBackground: Color = this.onBackground,
        surface: Color = this.surface,
        onSurface: Color = this.onSurface,
        surfaceVariant: Color = this.surfaceVariant,
        onSurfaceVariant: Color = this.onSurfaceVariant,
        error: Color = this.error,
        onError: Color = this.onError,
        errorContainer: Color = this.errorContainer,
        onErrorContainer: Color = this.onErrorContainer,
        accentBlue: Color = this.accentBlue,
        accentGreen: Color = this.accentGreen,
        accentOrange: Color = this.accentOrange,
        accentRed: Color = this.accentRed,
        isDark: Boolean = this.isDark,
    ): AppColors =
        AppColors(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            accentGreen = accentGreen,
            accentBlue = accentBlue,
            accentOrange = accentOrange,
            accentRed = accentRed,
            isDark = isDark,
        )
}

fun AppColors.contentColorFor(containerColor: Color) = when (containerColor) {
    primaryContainer -> onPrimaryContainer
    background -> onBackground
    surface -> onSurface
    primary -> onPrimary
    accentBlue -> onPrimary
    accentGreen -> onPrimary
    accentOrange -> onPrimary
    accentRed -> onPrimary
    else -> onBackground
}

@Suppress("LongParameterList")
private fun paletteColors(accent: Color, isDark: Boolean): AppColors {
    val accentArgb = accent.toArgb()
    val roles = MaterialColors.getColorRoles(accentArgb, !isDark)
    val primary = Color(roles.accent)
    val onPrimary = Color(roles.onAccent)
    val primaryContainer = Color(roles.accentContainer)
    val onPrimaryContainer = Color(roles.onAccentContainer)

    val hsv = floatArrayOf(0f, 0f, 0f)
    android.graphics.Color.RGBToHSV(
        android.graphics.Color.red(accentArgb),
        android.graphics.Color.green(accentArgb),
        android.graphics.Color.blue(accentArgb),
        hsv,
    )
    val secondaryHue = (hsv[0] + 120f) % 360f
    val secondaryArgb = android.graphics.Color.HSVToColor(floatArrayOf(secondaryHue, 0.3f, if (isDark) 0.8f else 0.4f))
    val secondaryRoles = MaterialColors.getColorRoles(secondaryArgb, !isDark)

    if (isDark) {
        return AppColors(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = Color(secondaryRoles.accent), onSecondary = Color(secondaryRoles.onAccent),
            secondaryContainer = Color(secondaryRoles.accentContainer),
            onSecondaryContainer = Color(secondaryRoles.onAccentContainer),
            tertiary = Color(secondaryRoles.accent), onTertiary = Color(secondaryRoles.onAccent),
            tertiaryContainer = Color(secondaryRoles.accentContainer),
            onTertiaryContainer = Color(secondaryRoles.onAccentContainer),
            outline = PaletteTokens.outlineDark,
            outlineVariant = PaletteTokens.outlineVariantDark,
            surface = PaletteTokens.surfaceDark,
            onSurface = PaletteTokens.textDarkPrimary,
            surfaceVariant = PaletteTokens.surfaceDarkSecondary,
            onSurfaceVariant = PaletteTokens.textDarkSecondary,
            background = PaletteTokens.surfaceDark,
            onBackground = PaletteTokens.textDarkPrimary,
            error = PaletteTokens.errorDark,
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            accentBlue = primary, accentGreen = PaletteTokens.successDark,
            accentOrange = PaletteTokens.warningDark, accentRed = PaletteTokens.errorDark,
            isDark = true,
        )
    }
    return AppColors(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = Color(secondaryRoles.accent), onSecondary = Color(secondaryRoles.onAccent),
        secondaryContainer = Color(secondaryRoles.accentContainer),
        onSecondaryContainer = Color(secondaryRoles.onAccentContainer),
        tertiary = Color(secondaryRoles.accent), onTertiary = Color(secondaryRoles.onAccent),
        tertiaryContainer = Color(secondaryRoles.accentContainer),
        onTertiaryContainer = Color(secondaryRoles.onAccentContainer),
        outline = PaletteTokens.outlineLight,
        outlineVariant = PaletteTokens.outlineVariantLight,
        surface = PaletteTokens.surfaceLight,
        onSurface = PaletteTokens.textLightPrimary,
        surfaceVariant = PaletteTokens.surfaceLightSecondary,
        onSurfaceVariant = PaletteTokens.textLightSecondary,
        background = PaletteTokens.surfaceLight,
        onBackground = PaletteTokens.textLightPrimary,
        error = PaletteTokens.errorLight,
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        accentBlue = primary, accentGreen = PaletteTokens.successLight,
        accentOrange = PaletteTokens.warningLight, accentRed = PaletteTokens.errorLight,
        isDark = false,
    )
}

internal fun lightColors() = paletteColors(PaletteTokens.darculaAccent, isDark = false)

internal fun darkColors() = paletteColors(PaletteTokens.darculaAccent, isDark = true)

@RequiresApi(Build.VERSION_CODES.S)
internal fun dynamicColors(context: Context, isDark: Boolean): AppColors {
    val dynamicColorScheme = if (isDark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    return AppColors(
        primary = dynamicColorScheme.primary, onPrimary = dynamicColorScheme.onPrimary,
        primaryContainer = dynamicColorScheme.primaryContainer, onPrimaryContainer = dynamicColorScheme.onPrimaryContainer,
        secondary = dynamicColorScheme.secondary, onSecondary = dynamicColorScheme.onSecondary,
        secondaryContainer = dynamicColorScheme.secondaryContainer, onSecondaryContainer = dynamicColorScheme.onSecondaryContainer,
        tertiary = dynamicColorScheme.tertiary, onTertiary = dynamicColorScheme.onTertiary,
        tertiaryContainer = dynamicColorScheme.tertiaryContainer, onTertiaryContainer = dynamicColorScheme.onTertiaryContainer,
        outline = dynamicColorScheme.outline, outlineVariant = dynamicColorScheme.outlineVariant,
        surface = dynamicColorScheme.surface, onSurface = dynamicColorScheme.onSurface,
        surfaceVariant = dynamicColorScheme.surfaceVariant, onSurfaceVariant = dynamicColorScheme.onSurfaceVariant,
        background = dynamicColorScheme.background, onBackground = dynamicColorScheme.onBackground,
        error = dynamicColorScheme.error.harmonize(dynamicColorScheme.primary, !isDark),
        onError = dynamicColorScheme.onError,
        errorContainer = dynamicColorScheme.errorContainer, onErrorContainer = dynamicColorScheme.onErrorContainer,
        accentBlue = dynamicColorScheme.primary, accentGreen = PaletteTokens.successLight,
        accentOrange = PaletteTokens.warningLight, accentRed = PaletteTokens.errorLight,
        isDark = isDark,
    )
}

private fun Color.harmonize(primary: Color, isLight: Boolean): Color {
    val accentColor = MaterialColors.harmonize(toArgb(), primary.toArgb())
    val accentRoles = MaterialColors.getColorRoles(accentColor, isLight)
    return Color(accentRoles.accent)
}

internal fun yoleColors(isDark: Boolean) = paletteColors(PaletteTokens.darculaAccent, isDark)
internal fun draculaColors(isDark: Boolean) = paletteColors(PaletteTokens.draculaAccent, isDark)
internal fun solarizedColors(isDark: Boolean) = paletteColors(PaletteTokens.solarizedAccent, isDark)
internal fun nordColors(isDark: Boolean) = paletteColors(PaletteTokens.nordAccent, isDark)
internal fun monokaiColors(isDark: Boolean) = paletteColors(PaletteTokens.monokaiAccent, isDark)
internal fun gruvboxColors(isDark: Boolean) = paletteColors(PaletteTokens.gruvboxAccent, isDark)
internal fun oneDarkColors(isDark: Boolean) = paletteColors(PaletteTokens.oneDarkAccent, isDark)
internal fun tokyoNightColors(isDark: Boolean) = paletteColors(PaletteTokens.tokyoNightAccent, isDark)

internal val LocalColors = staticCompositionLocalOf { lightColors() }

@Preview(
    group = "Colors light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    name = "Light theme",
    showBackground = true,
    backgroundColor = 0xEEEEEE,
)
@Preview(
    group = "Colors dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Dark theme",
    showBackground = true,
    backgroundColor = 0x111111,
)
@Composable
private fun ColorsPreview() {
    LavaTheme(isDynamic = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppTheme.spaces.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            mapOf(
                "primary" to AppTheme.colors.primary,
                "onPrimary" to AppTheme.colors.onPrimary,
                "primaryContainer" to AppTheme.colors.primaryContainer,
                "onPrimaryContainer" to AppTheme.colors.onPrimaryContainer,
                "outline" to AppTheme.colors.outline,
                "outlineVariant" to AppTheme.colors.outlineVariant,
                "surface" to AppTheme.colors.surface,
                "onSurface" to AppTheme.colors.onSurface,
                "background" to AppTheme.colors.background,
                "onBackground" to AppTheme.colors.onBackground,
                "error" to AppTheme.colors.error,
                "accentBlue" to AppTheme.colors.accentBlue,
                "accentGreen" to AppTheme.colors.accentGreen,
                "accentOrange" to AppTheme.colors.accentOrange,
                "accentRed" to AppTheme.colors.accentRed,
            ).entries.chunked(2).forEach { chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    chunk.forEach { (name, color) ->
                        Column(
                            modifier = Modifier
                                .padding(AppTheme.spaces.medium)
                                .weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(200.dp, 65.dp)
                                    .background(color, AppTheme.shapes.small),
                            )
                            Label(
                                modifier = Modifier.padding(top = AppTheme.spaces.small),
                                text = name,
                                color = AppTheme.colors.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}
