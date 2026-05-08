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
import lava.designsystem.theme.Accents.Companion.darkAccents
import lava.designsystem.theme.Accents.Companion.lightAccents

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
internal fun lightColors(
    primary: Color = Indigo40,
    onPrimary: Color = Indigo100,
    primaryContainer: Color = Indigo90,
    onPrimaryContainer: Color = Indigo10,
    secondary: Color = Studio40,
    onSecondary: Color = Studio100,
    secondaryContainer: Color = Studio90,
    onSecondaryContainer: Color = Studio10,
    tertiary: Color = Lipstick40,
    onTertiary: Color = Lipstick100,
    tertiaryContainer: Color = Lipstick90,
    onTertiaryContainer: Color = Lipstick10,
    outline: Color = MidGray50,
    outlineVariant: Color = MidGray80,
    background: Color = SanMarino99,
    onBackground: Color = MidGray10,
    surface: Color = SanMarino99,
    onSurface: Color = MidGray10,
    surfaceVariant: Color = SanMarino95,
    onSurfaceVariant: Color = MidGray30,
    error: Color = Thunderbird40,
    onError: Color = Thunderbird100,
    errorContainer: Color = Thunderbird90,
    onErrorContainer: Color = Thunderbird10,
    accentBlue: Color = lightAccents.accentBlue,
    accentGreen: Color = lightAccents.accentGreen,
    accentOrange: Color = lightAccents.accentOrange,
    accentRed: Color = lightAccents.accentRed,
) = AppColors(
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
    outline = outline,
    outlineVariant = outlineVariant,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    background = background,
    onBackground = onBackground,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    accentBlue = accentBlue,
    accentGreen = accentGreen,
    accentOrange = accentOrange,
    accentRed = accentRed,
    isDark = false,
)

@Suppress("LongParameterList")
internal fun darkColors(
    primary: Color = Indigo80,
    onPrimary: Color = Indigo20,
    primaryContainer: Color = Indigo30,
    onPrimaryContainer: Color = Indigo90,
    secondary: Color = Studio80,
    onSecondary: Color = Studio20,
    secondaryContainer: Color = Studio30,
    onSecondaryContainer: Color = Studio90,
    tertiary: Color = Lipstick80,
    onTertiary: Color = Lipstick20,
    tertiaryContainer: Color = Lipstick30,
    onTertiaryContainer: Color = Lipstick90,
    outline: Color = MidGray60,
    outlineVariant: Color = MidGray30,
    background: Color = MidGray10,
    onBackground: Color = MidGray90,
    surface: Color = MidGray10,
    onSurface: Color = MidGray90,
    surfaceVariant: Color = MidGray30,
    onSurfaceVariant: Color = MidGray80,
    error: Color = Thunderbird80,
    onError: Color = Thunderbird20,
    errorContainer: Color = Thunderbird30,
    onErrorContainer: Color = Thunderbird90,
    accentBlue: Color = darkAccents.accentBlue,
    accentGreen: Color = darkAccents.accentGreen,
    accentOrange: Color = darkAccents.accentOrange,
    accentRed: Color = darkAccents.accentRed,
) = AppColors(
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
    outline = outline,
    outlineVariant = outlineVariant,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    background = background,
    onBackground = onBackground,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    accentBlue = accentBlue,
    accentGreen = accentGreen,
    accentOrange = accentOrange,
    accentRed = accentRed,
    isDark = true,
)

@RequiresApi(Build.VERSION_CODES.S)
internal fun dynamicColors(context: Context, isDark: Boolean): AppColors {
    val dynamicColorScheme = if (isDark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    val accents = if (isDark) {
        darkAccents
    } else {
        lightAccents
    }
    return AppColors(
        primary = dynamicColorScheme.primary,
        onPrimary = dynamicColorScheme.onPrimary,
        primaryContainer = dynamicColorScheme.primaryContainer,
        onPrimaryContainer = dynamicColorScheme.onPrimaryContainer,
        secondary = dynamicColorScheme.secondary,
        onSecondary = dynamicColorScheme.onSecondary,
        secondaryContainer = dynamicColorScheme.secondaryContainer,
        onSecondaryContainer = dynamicColorScheme.onSecondaryContainer,
        tertiary = dynamicColorScheme.tertiary,
        onTertiary = dynamicColorScheme.onTertiary,
        tertiaryContainer = dynamicColorScheme.tertiaryContainer,
        onTertiaryContainer = dynamicColorScheme.onTertiaryContainer,
        outline = dynamicColorScheme.outline,
        outlineVariant = dynamicColorScheme.outlineVariant,
        surface = dynamicColorScheme.surface,
        onSurface = dynamicColorScheme.onSurface,
        surfaceVariant = dynamicColorScheme.surfaceVariant,
        onSurfaceVariant = dynamicColorScheme.onSurfaceVariant,
        background = dynamicColorScheme.background,
        onBackground = dynamicColorScheme.onBackground,
        error = dynamicColorScheme.error.harmonize(dynamicColorScheme.primary, !isDark),
        onError = dynamicColorScheme.onError,
        errorContainer = dynamicColorScheme.errorContainer,
        onErrorContainer = dynamicColorScheme.onErrorContainer,
        accentBlue = accents.accentBlue.harmonize(dynamicColorScheme.primary, !isDark),
        accentGreen = accents.accentGreen.harmonize(dynamicColorScheme.primary, !isDark),
        accentOrange = accents.accentOrange.harmonize(dynamicColorScheme.primary, !isDark),
        accentRed = accents.accentRed.harmonize(dynamicColorScheme.primary, !isDark),
        isDark = isDark,
    )
}

private fun Color.harmonize(primary: Color, isLight: Boolean): Color {
    val accentColor = MaterialColors.harmonize(toArgb(), primary.toArgb())
    val accentRoles = MaterialColors.getColorRoles(accentColor, isLight)
    return Color(accentRoles.accent)
}

private data class Accents(
    val accentBlue: Color,
    val accentGreen: Color,
    val accentOrange: Color,
    val accentRed: Color,
) {
    companion object {
        val lightAccents = Accents(
            accentBlue = Denim40,
            accentGreen = FunGreen40,
            accentOrange = Tabasco40,
            accentRed = Monza40,
        )
        val darkAccents = Accents(
            accentBlue = Denim80,
            accentGreen = FunGreen80,
            accentOrange = Tabasco80,
            accentRed = Monza80,
        )
    }
}

internal fun oceanColors(isDark: Boolean) = if (isDark) {
    AppColors(
        primary = Color(0xFF80CBC4),
        onPrimary = Color(0xFF003635),
        primaryContainer = Color(0xFF00504E),
        onPrimaryContainer = Color(0xFFA7F0E8),
        secondary = Color(0xFFB39DDB),
        onSecondary = Color(0xFF1E0A3E),
        secondaryContainer = Color(0xFF362654),
        onSecondaryContainer = Color(0xFFECDCFF),
        tertiary = Color(0xFF80DEEA),
        onTertiary = Color(0xFF00363F),
        tertiaryContainer = Color(0xFF004D59),
        onTertiaryContainer = Color(0xFFA6EEFF),
        outline = Color(0xFF899393),
        outlineVariant = Color(0xFF3F4949),
        surface = Color(0xFF0E1514),
        onSurface = Color(0xFFDDE4E3),
        surfaceVariant = Color(0xFF1A2524),
        onSurfaceVariant = Color(0xFFB9C7C6),
        background = Color(0xFF0E1514),
        onBackground = Color(0xFFDDE4E3),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        accentBlue = Color(0xFF64B5F6),
        accentGreen = Color(0xFF81C784),
        accentOrange = Color(0xFFFFB74D),
        accentRed = Color(0xFFE57373),
        isDark = true,
    )
} else {
    AppColors(
        primary = Color(0xFF00796B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB2DFDB),
        onPrimaryContainer = Color(0xFF00201D),
        secondary = Color(0xFF7E57C2),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD1C4E9),
        onSecondaryContainer = Color(0xFF1E0A3E),
        tertiary = Color(0xFF0097A7),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB2EBF2),
        onTertiaryContainer = Color(0xFF00363F),
        outline = Color(0xFF6F7979),
        outlineVariant = Color(0xFFBFC9C8),
        surface = Color(0xFFFBFDFC),
        onSurface = Color(0xFF191C1C),
        surfaceVariant = Color(0xFFE5EDEC),
        onSurfaceVariant = Color(0xFF454D4D),
        background = Color(0xFFFBFDFC),
        onBackground = Color(0xFF191C1C),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        accentBlue = Color(0xFF1565C0),
        accentGreen = Color(0xFF2E7D32),
        accentOrange = Color(0xFFE65100),
        accentRed = Color(0xFFC62828),
        isDark = false,
    )
}

internal fun forestColors(isDark: Boolean) = if (isDark) {
    AppColors(
        primary = Color(0xFFA5D6A7),
        onPrimary = Color(0xFF003910),
        primaryContainer = Color(0xFF005319),
        onPrimaryContainer = Color(0xFFC1EFC2),
        secondary = Color(0xFFBCAAA4),
        onSecondary = Color(0xFF2B1C17),
        secondaryContainer = Color(0xFF43322C),
        onSecondaryContainer = Color(0xFFD9C7C1),
        tertiary = Color(0xFFA5D6A7),
        onTertiary = Color(0xFF003910),
        tertiaryContainer = Color(0xFF005319),
        onTertiaryContainer = Color(0xFFC1EFC2),
        outline = Color(0xFF8D9388),
        outlineVariant = Color(0xFF43483F),
        surface = Color(0xFF11140E),
        onSurface = Color(0xFFE1E4DA),
        surfaceVariant = Color(0xFF25261F),
        onSurfaceVariant = Color(0xFFD0D4C7),
        background = Color(0xFF11140E),
        onBackground = Color(0xFFE1E4DA),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        accentBlue = Color(0xFF64B5F6),
        accentGreen = Color(0xFF81C784),
        accentOrange = Color(0xFFFFB74D),
        accentRed = Color(0xFFE57373),
        isDark = true,
    )
} else {
    AppColors(
        primary = Color(0xFF388E3C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC8E6C9),
        onPrimaryContainer = Color(0xFF002106),
        secondary = Color(0xFF795548),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD7CCC8),
        onSecondaryContainer = Color(0xFF2B1C17),
        tertiary = Color(0xFF388E3C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC8E6C9),
        onTertiaryContainer = Color(0xFF002106),
        outline = Color(0xFF73796D),
        outlineVariant = Color(0xFFC3C8BB),
        surface = Color(0xFFF9FAF0),
        onSurface = Color(0xFF1A1C18),
        surfaceVariant = Color(0xFFE4E6D8),
        onSurfaceVariant = Color(0xFF464B40),
        background = Color(0xFFF9FAF0),
        onBackground = Color(0xFF1A1C18),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        accentBlue = Color(0xFF1565C0),
        accentGreen = Color(0xFF2E7D32),
        accentOrange = Color(0xFF6D4C41),
        accentRed = Color(0xFFC62828),
        isDark = false,
    )
}

internal fun sunsetColors(isDark: Boolean) = if (isDark) {
    AppColors(
        primary = Color(0xFFFFB74D),
        onPrimary = Color(0xFF462A00),
        primaryContainer = Color(0xFF633E00),
        onPrimaryContainer = Color(0xFFFFDDB2),
        secondary = Color(0xFF90CAF9),
        onSecondary = Color(0xFF001D33),
        secondaryContainer = Color(0xFF003354),
        onSecondaryContainer = Color(0xFFD0E8FF),
        tertiary = Color(0xFFEF9A9A),
        onTertiary = Color(0xFF3E0400),
        tertiaryContainer = Color(0xFF5C110B),
        onTertiaryContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF938A80),
        outlineVariant = Color(0xFF494137),
        surface = Color(0xFF15120C),
        onSurface = Color(0xFFEDE0D2),
        surfaceVariant = Color(0xFF2B241B),
        onSurfaceVariant = Color(0xFFD6C3B7),
        background = Color(0xFF15120C),
        onBackground = Color(0xFFEDE0D2),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        accentBlue = Color(0xFF64B5F6),
        accentGreen = Color(0xFF81C784),
        accentOrange = Color(0xFFFFB74D),
        accentRed = Color(0xFFE57373),
        isDark = true,
    )
} else {
    AppColors(
        primary = Color(0xFFE65100),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCC2),
        onPrimaryContainer = Color(0xFF331200),
        secondary = Color(0xFF5C6BC0),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC5CAE9),
        onSecondaryContainer = Color(0xFF001D33),
        tertiary = Color(0xFFD32F2F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFCDD2),
        onTertiaryContainer = Color(0xFF3E0400),
        outline = Color(0xFF84746A),
        outlineVariant = Color(0xFFD6C3B7),
        surface = Color(0xFFFFF8F5),
        onSurface = Color(0xFF201A14),
        surfaceVariant = Color(0xFFF2E2D8),
        onSurfaceVariant = Color(0xFF524741),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF201A14),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        accentBlue = Color(0xFF1565C0),
        accentGreen = Color(0xFF2E7D32),
        accentOrange = Color(0xFFE65100),
        accentRed = Color(0xFF7B1FA2),
        isDark = false,
    )
}

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
