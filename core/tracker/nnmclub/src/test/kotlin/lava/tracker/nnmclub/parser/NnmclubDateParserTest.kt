package lava.tracker.nnmclub.parser

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Anti-bluff unit test for [NnmclubDateParser] — a standalone production object
 * with branching logic (NBSP normalization, empty-input short-circuit, ISO
 * parse success, parse failure -> null) that previously had NO test, while its
 * sibling [lava.tracker.rutor.parser.RuTorDateParser] is fully covered.
 *
 * Primary assertion is on the returned user-visible value: the parsed [Instant]
 * truncated to UTC start-of-day, which is what nnmclub.to search/browse rows
 * render as their posted-on date. The SUT is the real production object; there
 * are no mocks and no boundaries to fake (the parser is pure).
 *
 * FALSIFIABILITY REHEARSAL: breaking the production truncation (replacing
 * `.atStartOfDay(ZoneOffset.UTC)` with `.atTime(12, 0).toInstant(ZoneOffset.UTC)`)
 * makes `parses ISO date as UTC start-of-day instant` FAIL with a clear Instant
 * inequality assertion. Recorded in the task evidence.
 */
class NnmclubDateParserTest {

    @Test
    fun `parses ISO date as UTC start-of-day instant`() {
        val result = NnmclubDateParser.parse("2024-01-15")

        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), result)
    }

    @Test
    fun `parses leap-day ISO date`() {
        val result = NnmclubDateParser.parse("2024-02-29")

        assertEquals(Instant.parse("2024-02-29T00:00:00Z"), result)
    }

    @Test
    fun `non-breaking space around the date is normalized before parsing`() {
        // nnmclub HTML cells routinely carry a leading/trailing U+00A0
        // (non-breaking space). java.time's ISO_LOCAL_DATE rejects those bytes,
        // and a bare trim() does NOT strip U+00A0 — so the parser first
        // replaces ' ' with a regular space, which trim() can then remove.
        // This case exercises the replace(' ', ' ') branch specifically.
        val result = NnmclubDateParser.parse(" 2024-03-07 ")

        assertEquals(Instant.parse("2024-03-07T00:00:00Z"), result)
    }

    @Test
    fun `empty string returns null`() {
        assertNull(NnmclubDateParser.parse(""))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(NnmclubDateParser.parse("   "))
    }

    @Test
    fun `invalid calendar date returns null`() {
        // Feb 29 in a non-leap year is not a valid calendar date.
        assertNull(NnmclubDateParser.parse("2023-02-29"))
    }

    @Test
    fun `non-ISO date format returns null`() {
        assertNull(NnmclubDateParser.parse("15.01.2024"))
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(NnmclubDateParser.parse("not a date"))
    }
}
