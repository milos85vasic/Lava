package lava.tracker.rutracker.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cyrillic size-unit boundary for [RuTrackerSizeParser].
 *
 * [RuTrackerSizeParserTest] covers the Latin-unit forms ("4.7 GB", "1024 MB",
 * …). It does NOT cover the Cyrillic units (ГБ / МБ / КБ / Б / ТБ) that
 * rutracker.org renders in its NATIVE size column. The parser's regex
 * alternation is Latin-only — `(GB|MB|KB|B|TB)` — so a Cyrillic-unit string
 * genuinely parses to null today. Rather than assert a false success, these
 * tests pin the parser's ACTUAL behaviour (Cyrillic → null), documenting the
 * limitation so a later extension is a deliberate edit to THIS test, and
 * confirming the Latin path still works when a Cyrillic prefix precedes a Latin
 * token.
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2):
 *   Mutation A — add `ГБ|МБ|…` to the regex → the Cyrillic-null assertion FAILS.
 *   Mutation B — `find` → `matchEntire` → the Latin-after-Cyrillic case FAILS.
 */
class RuTrackerSizeParserCyrillicUnitTest {

    @Test
    fun `Cyrillic units parse to null today (Latin-only regex boundary)`() {
        assertNull("Cyrillic ГБ parses to null today", RuTrackerSizeParser.parse("4.7 ГБ"))
        assertNull("Cyrillic МБ parses to null today", RuTrackerSizeParser.parse("700 МБ"))
        assertNull("Cyrillic КБ parses to null today", RuTrackerSizeParser.parse("500 КБ"))
        assertNull("Cyrillic Б parses to null today", RuTrackerSizeParser.parse("123 Б"))
        assertNull("Cyrillic ТБ parses to null today", RuTrackerSizeParser.parse("2 ТБ"))
    }

    @Test
    fun `comma-decimal Cyrillic unit also parses to null`() {
        assertNull(RuTrackerSizeParser.parse("1,5 ГБ"))
        assertNull(RuTrackerSizeParser.parse("0,5 КБ"))
    }

    @Test
    fun `Latin token after a Cyrillic prefix is still found (first parseable token wins)`() {
        assertEquals(5_046_586_572L, RuTrackerSizeParser.parse("Размер: 4.7 GB"))
        assertEquals(1_073_741_824L, RuTrackerSizeParser.parse("примерно 1 GB"))
        assertEquals(700L * 1024L * 1024L, RuTrackerSizeParser.parse("ГБ 700 MB"))
    }
}
