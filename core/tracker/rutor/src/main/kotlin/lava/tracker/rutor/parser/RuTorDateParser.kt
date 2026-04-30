package lava.tracker.rutor.parser

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Parses RuTor's posted-on date stamps. The site uses three concrete shapes:
 *   - "Сегодня" (today, lowercase or capitalized)
 *   - "Вчера" (yesterday, ditto)
 *   - "<day> <month-abbreviation> <year>" e.g. "12 Янв 24" or "5 Дек 2023"
 *
 * Months are Russian abbreviations (3 chars). Years may be 2-digit (2000-prefixed)
 * or 4-digit. Returns the parsed [Instant] truncated to the start of the UTC day,
 * or null if [s] is unparsable. The [now] lambda is injected so tests can pin
 * "today" / "yesterday" without depending on wall-clock time.
 */
object RuTorDateParser {
    private val months: Map<String, Int> = mapOf(
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

    private val numericPattern = Regex("""(\d{1,2})\s+(\S+)\s+(\d{2,4})""")

    fun parse(s: String, now: () -> Instant = Clock.System::now): Instant? {
        val trimmed = s.trim()
        return when {
            trimmed.equals("Сегодня", ignoreCase = true) ->
                now().toJavaInstant().truncatedTo(ChronoUnit.DAYS).toKotlinInstant()
            trimmed.equals("Вчера", ignoreCase = true) ->
                now().toJavaInstant()
                    .truncatedTo(ChronoUnit.DAYS)
                    .minus(1, ChronoUnit.DAYS)
                    .toKotlinInstant()
            else -> {
                val match = numericPattern.find(trimmed) ?: return null
                val (dayStr, monthAbbr, yearStr) = match.destructured
                val month = months[monthAbbr.take(3)] ?: return null
                val year = yearStr.toIntOrNull() ?: return null
                val fullYear = if (yearStr.length == 2) 2000 + year else year
                val day = dayStr.toIntOrNull() ?: return null
                runCatching {
                    LocalDate.of(fullYear, month, day)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toKotlinInstant()
                }.getOrNull()
            }
        }
    }
}
