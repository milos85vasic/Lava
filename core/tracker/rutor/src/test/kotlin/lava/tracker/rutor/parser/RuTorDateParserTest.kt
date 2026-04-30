package lava.tracker.rutor.parser

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuTorDateParserTest {

    /** A pinned "now" so Сегодня / Вчера are deterministic in tests. */
    private val fixedNow: () -> Instant = { Instant.parse("2026-04-30T15:42:11Z") }

    @Test
    fun `parses 12 Jan 24 as 2024-01-12 UTC midnight`() {
        val r = RuTorDateParser.parse("12 Янв 24", fixedNow)
        assertEquals(Instant.parse("2024-01-12T00:00:00Z"), r)
    }

    @Test
    fun `parses 5 Dec 2023 with 4-digit year as 2023-12-05 UTC midnight`() {
        val r = RuTorDateParser.parse("5 Дек 2023", fixedNow)
        assertEquals(Instant.parse("2023-12-05T00:00:00Z"), r)
    }

    @Test
    fun `parses Февраль leap day 29 Feb 24`() {
        val r = RuTorDateParser.parse("29 Фев 24", fixedNow)
        assertEquals(Instant.parse("2024-02-29T00:00:00Z"), r)
    }

    @Test
    fun `Сегодня returns today truncated to UTC midnight`() {
        val r = RuTorDateParser.parse("Сегодня", fixedNow)
        assertEquals(Instant.parse("2026-04-30T00:00:00Z"), r)
    }

    @Test
    fun `Сегодня is case-insensitive`() {
        val r = RuTorDateParser.parse("сегодня", fixedNow)
        assertEquals(Instant.parse("2026-04-30T00:00:00Z"), r)
    }

    @Test
    fun `Вчера returns yesterday truncated to UTC midnight`() {
        val r = RuTorDateParser.parse("Вчера", fixedNow)
        assertEquals(Instant.parse("2026-04-29T00:00:00Z"), r)
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(RuTorDateParser.parse("not a date", fixedNow))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(RuTorDateParser.parse("", fixedNow))
    }

    @Test
    fun `unknown month abbreviation returns null`() {
        assertNull(RuTorDateParser.parse("12 Xyz 24", fixedNow))
    }

    @Test
    fun `all 12 Russian month abbreviations parse to the right month number`() {
        val cases = listOf(
            "Янв" to 1,
            "Фев" to 2,
            "Мар" to 3,
            "Апр" to 4,
            "Май" to 5,
            "Июн" to 6,
            "Июл" to 7,
            "Авг" to 8,
            "Сен" to 9,
            "Окт" to 10,
            "Ноя" to 11,
            "Дек" to 12,
        )
        for ((abbr, monthNumber) in cases) {
            val day = if (monthNumber == 2) 28 else 15
            val r = RuTorDateParser.parse("$day $abbr 24", fixedNow)
            assertNotNull("Expected non-null parse for $abbr", r)
            val mm = monthNumber.toString().padStart(2, '0')
            val dd = day.toString().padStart(2, '0')
            assertEquals(
                "Month $abbr should map to $mm",
                Instant.parse("2024-$mm-${dd}T00:00:00Z"),
                r,
            )
        }
    }
}
