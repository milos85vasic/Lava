package lava.tracker.rutracker.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Regression for the §6.Q/§6.R-adjacent ImplicitDefaultLocale defect at
 * Utils.kt:102 surfaced by the Detekt correctness backlog (completeness
 * Phase 2 → fixed Phase 3).
 *
 * `formatSize` rendered its decimal via `String.format("%.1f …")` WITHOUT a
 * Locale, so the JVM picked up `Locale.getDefault()`. On any comma-decimal
 * locale (de, ru, fr, …) a 1.5 MB torrent rendered as "1,5 MB" — the wrong
 * separator for a user whose UI strings are otherwise dot-decimal, and a
 * value that the project's own `RuTrackerSizeParser` round-trip and any
 * size-based filter could mis-handle.
 *
 * Primary assertion is the rendered user-visible String. The test forces a
 * comma-decimal default locale so the bug is observable on the host CI
 * regardless of the developer's machine locale.
 *
 * Falsifiability: revert Utils.kt:102 to the Locale-less `String.format`
 * and `formats decimal sizes with a dot on a comma-decimal locale` fails
 * with `expected "1.5 MB" but was "1,5 MB"`.
 */
class FormatSizeLocaleTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formats decimal sizes with a dot on a comma-decimal locale`() {
        Locale.setDefault(Locale.GERMANY)
        // 1.5 MiB = 1.5 * 2^20 = 1_572_864 bytes
        assertEquals("1.5 MB", formatSize(1_572_864L))
    }

    @Test
    fun `formats decimal GB with a dot on a comma-decimal locale`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        // 4.7 GiB exact-ish: 5_046_586_572 bytes / 2^30 = 4.700000... -> "4.7 GB"
        assertEquals("4.7 GB", formatSize(5_046_586_572L))
    }

    @Test
    fun `bare-bytes path is locale-independent`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("512 B", formatSize(512L))
    }

    @Test
    fun `formats with a dot on a dot-decimal locale too`() {
        Locale.setDefault(Locale.US)
        assertEquals("1.5 MB", formatSize(1_572_864L))
    }
}
