package lava.tracker.rutracker.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LF-6 RESOLVED 2026-04-30 — falsifiability rehearsal recorded in the
 * commit body.
 *
 * Primary assertions are on the parsed binary byte count — a real,
 * size-comparable value the SDK consumer (cross-tracker fallback
 * ranking, size-based filters) reads from `TorrentItem.sizeBytes`.
 * Nothing in this test class is satisfied by a "did not crash" smoke
 * check; every test is an exact-equals on the parsed Long.
 */
class RuTrackerSizeParserTest {

    @Test
    fun `parses integer GB as power-of-1024`() {
        // 1 * 2^30 = 1_073_741_824
        assertEquals(1_073_741_824L, RuTrackerSizeParser.parse("1 GB"))
    }

    @Test
    fun `parses decimal GB with period`() {
        // 4.7 * 2^30 = 5_046_586_572 (4.7 * 1073741824 = 5046586572.8 -> Long truncates)
        assertEquals(5_046_586_572L, RuTrackerSizeParser.parse("4.7 GB"))
    }

    @Test
    fun `parses decimal GB with comma`() {
        // 4,7 GB == 4.7 GB
        assertEquals(5_046_586_572L, RuTrackerSizeParser.parse("4,7 GB"))
    }

    @Test
    fun `parses MB`() {
        // 1.2 MB = 1.2 * 2^20 = 1_258_291 (truncated)
        assertEquals(1_258_291L, RuTrackerSizeParser.parse("1.2 MB"))
    }

    @Test
    fun `parses 1024 MB equals 1 GiB`() {
        assertEquals(1_073_741_824L, RuTrackerSizeParser.parse("1024 MB"))
    }

    @Test
    fun `parses KB`() {
        assertEquals(500L * 1024L, RuTrackerSizeParser.parse("500 KB"))
    }

    @Test
    fun `parses bare bytes`() {
        assertEquals(123L, RuTrackerSizeParser.parse("123 B"))
    }

    @Test
    fun `parses TB`() {
        // 5.4 * 2^40 = 5_937_362_789_990.4 -> Long truncates to 5_937_362_789_990.
        // (Double precision matters at the 2^40 scale; the integer 2-TB
        // case below is a tighter check, this one anchors the decimal-TB
        // behaviour.)
        assertEquals(5_937_362_789_990L, RuTrackerSizeParser.parse("5.4 TB"))
    }

    @Test
    fun `parses integer 2 TB as exact power-of-1024`() {
        // 2 * 2^40 = 2_199_023_255_552 — no double-precision rounding loss.
        assertEquals(2_199_023_255_552L, RuTrackerSizeParser.parse("2 TB"))
    }

    @Test
    fun `case insensitive on the unit`() {
        assertEquals(1_073_741_824L, RuTrackerSizeParser.parse("1 gb"))
        assertEquals(1_048_576L, RuTrackerSizeParser.parse("1 mb"))
    }

    @Test
    fun `null input returns null without throwing`() {
        assertNull(RuTrackerSizeParser.parse(null))
    }

    @Test
    fun `blank input returns null without throwing`() {
        assertNull(RuTrackerSizeParser.parse(""))
        assertNull(RuTrackerSizeParser.parse("   "))
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(RuTrackerSizeParser.parse("not a size"))
        assertNull(RuTrackerSizeParser.parse("123 PB")) // PB not supported
    }

    @Test
    fun `non-breaking space between number and unit is tolerated`() {
        // The rutracker scraper sometimes leaves U+00A0 between the number
        // and the unit — parser must normalise it to a regular space.
        assertEquals(1_073_741_824L, RuTrackerSizeParser.parse("1 GB"))
    }

    @Test
    fun `no whitespace between number and unit`() {
        assertEquals(1_073_741_824L, RuTrackerSizeParser.parse("1GB"))
    }
}
