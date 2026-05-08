package lava.designsystem.theme

import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PaletteContractTest {

    @Test
    fun `all 8 palettes produce valid light colors`() {
        val factories = listOf(
            { yoleColors(false) }, { draculaColors(false) }, { solarizedColors(false) },
            { nordColors(false) }, { monokaiColors(false) }, { gruvboxColors(false) },
            { oneDarkColors(false) }, { tokyoNightColors(false) },
        )
        for (factory in factories) {
            val colors = factory()
            assertNotNull("light colors must not be null", colors)
            assertTrue("light must report isDark=false", !colors.isDark)
            assertTrue("primary must not equal surface", colors.primary != colors.surface)
        }
    }

    @Test
    fun `all 8 palettes produce valid dark colors`() {
        val factories = listOf(
            { yoleColors(true) }, { draculaColors(true) }, { solarizedColors(true) },
            { nordColors(true) }, { monokaiColors(true) }, { gruvboxColors(true) },
            { oneDarkColors(true) }, { tokyoNightColors(true) },
        )
        for (factory in factories) {
            val colors = factory()
            assertNotNull("dark colors must not be null", colors)
            assertTrue("dark must report isDark=true", colors.isDark)
            assertTrue("primary must not equal surface", colors.primary != colors.surface)
        }
    }

    @Test
    fun `each palette has distinct primary color`() {
        val primaries = listOf(
            PaletteTokens.darculaAccent, PaletteTokens.draculaAccent,
            PaletteTokens.solarizedAccent, PaletteTokens.nordAccent,
            PaletteTokens.monokaiAccent, PaletteTokens.gruvboxAccent,
            PaletteTokens.oneDarkAccent, PaletteTokens.tokyoNightAccent,
        )
        assertEquals("all 8 primaries must be unique", 8, primaries.distinct().size)
    }

    @Test
    fun `light text contrast is acceptable`() {
        val colors = yoleColors(false)
        val contrast = abs(colors.onSurface.luminance() - colors.surface.luminance())
        assertTrue("light mode text contrast must be positive", contrast > 0.0)
    }

    @Test
    fun `dark text contrast is acceptable`() {
        val colors = yoleColors(true)
        val contrast = abs(colors.onSurface.luminance() - colors.surface.luminance())
        assertTrue("dark mode text contrast must be positive", contrast > 0.0)
    }

    @Test
    fun `default lightColors and darkColors use Yole palette`() {
        val light = lightColors()
        val dark = darkColors()
        assertTrue("default light must not be dark", !light.isDark)
        assertTrue("default dark must be dark", dark.isDark)
    }
}
