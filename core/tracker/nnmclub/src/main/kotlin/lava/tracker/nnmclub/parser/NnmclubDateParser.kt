package lava.tracker.nnmclub.parser

import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parses nnmclub.to search/browse posted-on date stamps.
 *
 * The search-result `Date` column emits an ISO calendar date (`yyyy-MM-dd`,
 * e.g. `2024-01-15`). Returns the parsed [Instant] truncated to the start of the
 * UTC day, or null if [s] is empty / unparsable. A stand-alone object so it can
 * be unit-tested in isolation and reused by the browse parser if its date column
 * shares the same shape.
 */
object NnmclubDateParser {

    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(s: String): Instant? {
        val trimmed = s.replace(' ', ' ').trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            LocalDate.parse(trimmed, isoDate)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toKotlinInstant()
        }.getOrNull()
    }
}
