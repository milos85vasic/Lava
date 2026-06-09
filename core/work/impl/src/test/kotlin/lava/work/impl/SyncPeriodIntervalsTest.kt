package lava.work.impl

import lava.models.settings.SyncPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Real-stack coverage for the production [SyncPeriod.repeatIntervalMillis] /
 * [SyncPeriod.flexIntervalMillis] mapping that [WorkBackgroundService] feeds
 * into WorkManager's `PeriodicWorkRequestBuilder(repeat, flex)`.
 *
 * Anti-Bluff posture (Second Law — no testing a copy): these tests call the
 * REAL `internal` extension properties used in production (the same `when`
 * mappings `periodicWorkRequest` consumes), not a re-implemented table.
 *
 * Why this matters for the user (Sixth Law clause 3): WorkManager requires
 * `flexInterval <= repeatInterval`. The flex window is the tail of each repeat
 * period during which the periodic sync (favorites / bookmarks / history /
 * credentials) is allowed to run. When `flexInterval > repeatInterval`,
 * WorkManager silently clamps flex to the full period — so the user's
 * intended fractional flex window is lost, and the sync schedule degrades to
 * "run anytime in the period" instead of "run near the end of the period".
 *
 * Regression target: [SyncPeriod.DAY] previously mapped flex to `6.days`
 * against a `1.day` repeat interval (6x too large), a `6.days`-for-`6.hours`
 * typo. That clamped DAY's flex to the whole 24h period.
 */
class SyncPeriodIntervalsTest {

    /**
     * Every periodic [SyncPeriod] MUST satisfy WorkManager's contract
     * `flexInterval <= repeatInterval`. Before the fix, DAY violated this
     * (flex 6 days > repeat 1 day).
     */
    @Test
    fun `flex interval never exceeds repeat interval for any periodic SyncPeriod`() {
        SyncPeriod.entries
            .filter { it != SyncPeriod.OFF }
            .forEach { period ->
                val repeat = period.repeatIntervalMillis
                val flex = period.flexIntervalMillis
                assertTrue(
                    "SyncPeriod.$period: flexInterval ($flex ms) MUST be <= " +
                        "repeatInterval ($repeat ms) or WorkManager clamps it to " +
                        "the full period, destroying the flex window",
                    flex <= repeat,
                )
            }
    }

    /**
     * DAY's flex MUST be a sub-day window. Pins the corrected value (6h, a
     * quarter of the 24h period — the same 1/4 ratio HOUR uses) so the `6.days`
     * regression cannot silently return.
     */
    @Test
    fun `DAY sync flex is six hours, not six days`() {
        assertEquals(
            6.hours.inWholeMilliseconds,
            SyncPeriod.DAY.flexIntervalMillis,
        )
        // Cross-check it is strictly inside the 1-day repeat period.
        assertTrue(
            "DAY flex MUST be strictly less than the 1-day repeat period",
            SyncPeriod.DAY.flexIntervalMillis < 1.days.inWholeMilliseconds,
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(6),
            SyncPeriod.DAY.flexIntervalMillis,
        )
    }

    /** OFF is not a schedulable period; both intervals are zero. */
    @Test
    fun `OFF maps to zero intervals`() {
        assertEquals(0L, SyncPeriod.OFF.repeatIntervalMillis)
        assertEquals(0L, SyncPeriod.OFF.flexIntervalMillis)
    }
}
