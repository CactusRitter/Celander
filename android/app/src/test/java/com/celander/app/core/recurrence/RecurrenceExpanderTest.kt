package com.celander.app.core.recurrence

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RecurrenceExpanderTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hour = 3_600_000L

    private fun millis(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, zone: ZoneId = berlin) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun local(ms: Long, zone: ZoneId = berlin) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    private fun expand(
        start: Long,
        rule: String,
        from: Long,
        to: Long,
        exdates: Set<Long> = emptySet(),
        allDay: Boolean = false,
        duration: Long = hour,
    ) = RecurrenceExpander.expand(start, duration, berlin, allDay, RRule.parse(rule)!!, exdates, from, to)

    @Test
    fun weeklyByDayKeepsWallClockAcrossDst() {
        // DST ends on 25 Oct 2026 in Europe.
        val start = millis(2026, 10, 19, 9)
        val result = expand(start, "FREQ=WEEKLY;BYDAY=MO,WE", start, millis(2026, 11, 1))
        assertEquals(
            listOf(
                LocalDateTime.of(2026, 10, 19, 9, 0),
                LocalDateTime.of(2026, 10, 21, 9, 0),
                LocalDateTime.of(2026, 10, 26, 9, 0),
                LocalDateTime.of(2026, 10, 28, 9, 0),
            ),
            result.map { local(it) },
        )
    }

    @Test
    fun countIncludesExcludedOccurrences() {
        val start = millis(2026, 1, 1, 10)
        val excluded = setOf(millis(2026, 1, 2, 10))
        val result = expand(start, "FREQ=DAILY;COUNT=3", start, millis(2026, 2, 1), excluded)
        assertEquals(listOf(1, 3), result.map { local(it).dayOfMonth })
    }

    @Test
    fun untilIsInclusive() {
        val start = millis(2026, 3, 1, 8)
        val result = expand(start, "FREQ=DAILY;UNTIL=20260303T070000Z", start, millis(2026, 4, 1))
        assertEquals(listOf(1, 2, 3), result.map { local(it).dayOfMonth })
    }

    @Test
    fun monthlyLastFriday() {
        val start = millis(2026, 1, 30, 18)
        val result = expand(start, "FREQ=MONTHLY;BYDAY=-1FR", start, millis(2026, 5, 1))
        assertEquals(
            listOf(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 27), LocalDate.of(2026, 4, 24)),
            result.map { local(it).toLocalDate() },
        )
    }

    @Test
    fun monthlyOn31stSkipsShortMonths() {
        val start = millis(2026, 1, 31, 12)
        val result = expand(start, "FREQ=MONTHLY", start, millis(2026, 6, 1))
        assertEquals(listOf(1, 3, 5), result.map { local(it).monthValue })
    }

    @Test
    fun yearlyAllDayBirthday() {
        val start = LocalDate.of(2000, 2, 29).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val from = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val to = LocalDate.of(2033, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val result = expand(start, "FREQ=YEARLY", from, to, allDay = true, duration = 86_400_000L)
        assertEquals(listOf(2028, 2032), result.map { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year })
    }

    @Test
    fun windowFarInFutureOfOldDailySeries() {
        val start = millis(2010, 1, 1, 7)
        val from = millis(2026, 10, 23)
        val result = expand(start, "FREQ=DAILY;INTERVAL=2", from, millis(2026, 10, 30))
        assertEquals(3, result.size)
        result.forEach { assertEquals(7, local(it).hour) }
    }

    @Test
    fun bySetPosLastWorkday() {
        val start = millis(2026, 1, 30, 9)
        val result = expand(start, "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", start, millis(2026, 4, 1))
        assertEquals(
            listOf(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 31)),
            result.map { local(it).toLocalDate() },
        )
    }

    @Test
    fun formatRoundTrip() {
        val rule = "FREQ=MONTHLY;INTERVAL=2;BYDAY=2TU,-1FR;WKST=SU"
        assertEquals(rule, RRule.parse(rule)!!.format())
    }
}
