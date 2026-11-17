package com.celander.app.core.recurrence

import com.celander.app.testing.BERLIN
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Sub-daily series that started long ago, the cap on occurrences, and yearly rules without BYMONTH. */
class RecurrenceExpanderRulesTest {
    private val hour = 3_600_000L

    private fun millis(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0).atZone(BERLIN).toInstant().toEpochMilli()

    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(BERLIN).toLocalDateTime()

    private fun expand(start: Long, rule: String, from: Long, to: Long, duration: Long = hour, limit: Int = RecurrenceExpander.MAX_INSTANCES) =
        RecurrenceExpander.expand(start, duration, BERLIN, false, RRule.parse(rule)!!, emptySet(), from, to, limit)

    @Test
    fun hourlySeriesStartedYearsAgoStillShows() {
        // More than 60 000 hours lie between the start and the window.
        val start = millis(2019, 1, 1, 9)
        val day = expand(start, "FREQ=HOURLY", millis(2026, 10, 23), millis(2026, 10, 24))
        assertEquals((0..23).toList(), day.map { local(it).hour })
        assertTrue(day.all { local(it).toLocalDate() == LocalDate.of(2026, 10, 23) })
        // The steps stay exact hours: the day summer time ends has 25 of them.
        assertEquals(25, expand(start, "FREQ=HOURLY", millis(2026, 10, 25), millis(2026, 10, 26)).size)
        val halfHours = expand(start, "FREQ=MINUTELY;INTERVAL=30", millis(2026, 10, 23), millis(2026, 10, 23, 2), duration = hour / 2)
        assertEquals(listOf(0, 30, 0, 30), halfHours.map { local(it).minute })
    }

    @Test
    fun limitRaisesTheCapOnOccurrences() {
        val start = millis(2020, 1, 1, 9)
        val end = start + 3_100L * 86_400_000L
        val rule = "FREQ=DAILY;COUNT=3000"
        assertEquals(RecurrenceExpander.MAX_INSTANCES, expand(start, rule, start, end).size)
        assertEquals(3_000, expand(start, rule, start, end, limit = 5_000).size)

        val event = timedEvent(1, LocalDateTime.of(2020, 1, 1, 9, 0), 60, rrule = rule)
        val parsed = RRule.parse(rule)!!
        val from = Instant.ofEpochMilli(start)
        val until = Instant.ofEpochMilli(end)
        assertEquals(RecurrenceExpander.MAX_INSTANCES, OccurrenceCalculator.seriesKeys(event, parsed, emptySet(), from, until).size)
        assertEquals(3_000, OccurrenceCalculator.seriesKeys(event, parsed, emptySet(), from, until, limit = 5_000).size)
    }

    @Test
    fun yearlyMonthDayWithoutMonthRepeatsInEveryMonth() {
        val start = millis(2026, 1, 15, 9)
        val result = expand(start, "FREQ=YEARLY;BYMONTHDAY=15", start, millis(2027, 1, 1))
        assertEquals((1..12).toList(), result.map { local(it).monthValue })
        assertTrue(result.all { local(it).dayOfMonth == 15 && local(it).hour == 9 })
    }

    @Test
    fun yearlyWeekdayAndMonthDayWithoutMonthLooksAtEveryMonth() {
        val start = millis(2026, 2, 13, 9)
        val result = expand(start, "FREQ=YEARLY;BYDAY=FR;BYMONTHDAY=13", start, millis(2027, 1, 1))
        assertEquals(
            listOf(LocalDate.of(2026, 2, 13), LocalDate.of(2026, 3, 13), LocalDate.of(2026, 11, 13)),
            result.map { local(it).toLocalDate() },
        )
    }

    @Test
    fun yearlyNumberedWeekdayWithoutMonthCountsThroughTheYear() {
        val start = millis(2026, 5, 18, 9)
        val result = expand(start, "FREQ=YEARLY;BYDAY=20MO", start, millis(2028, 1, 1))
        assertEquals(listOf(LocalDate.of(2026, 5, 18), LocalDate.of(2027, 5, 17)), result.map { local(it).toLocalDate() })
    }
}
