package com.celander.app.event

import com.celander.app.testing.BERLIN
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class RepeatRulesTest {
    private val friday = LocalDate.of(2026, 10, 23)

    private fun roundTrip(spec: RepeatSpec, start: LocalDate = friday, allDay: Boolean = false): Pair<String?, RepeatSpec> {
        val rule = RepeatRules.toRRule(spec, start, allDay, BERLIN)
        return rule to RepeatRules.fromRRule(rule, start, BERLIN)
    }

    @Test
    fun weeklyOnTheStartDayIsThePlainRule() {
        val (rule, back) = roundTrip(RepeatSpec(RepeatKind.WEEKLY, weekdays = setOf(DayOfWeek.FRIDAY)))
        assertEquals("FREQ=WEEKLY", rule)
        assertEquals(setOf(DayOfWeek.FRIDAY), back.weekdays)
    }

    @Test
    fun weeklyOnSeveralDaysWithInterval() {
        val (rule, back) = roundTrip(RepeatSpec(RepeatKind.WEEKLY, interval = 2, weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", rule)
        assertEquals(RepeatKind.WEEKLY, back.kind)
        assertEquals(2, back.interval)
    }

    @Test
    fun monthlyOnTheLastWeekdayWorksInEveryMonth() {
        // 30 October 2026 is the last Friday of the month.
        val (rule, back) = roundTrip(RepeatSpec(RepeatKind.MONTHLY_WEEKDAY), start = LocalDate.of(2026, 10, 30))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", rule)
        assertEquals(RepeatKind.MONTHLY_WEEKDAY, back.kind)
        assertEquals("FREQ=MONTHLY;BYDAY=4FR", RepeatRules.toRRule(RepeatSpec(RepeatKind.MONTHLY_WEEKDAY), friday, false, BERLIN))
    }

    @Test
    fun endDateIsTheWholeLocalDay() {
        val spec = RepeatSpec(RepeatKind.DAILY, end = RepeatEnd.UNTIL, until = LocalDate.of(2026, 11, 30))
        assertEquals("FREQ=DAILY;UNTIL=20261130T225959Z", RepeatRules.toRRule(spec, friday, false, BERLIN))
        assertEquals("FREQ=DAILY;UNTIL=20261130", RepeatRules.toRRule(spec, friday, true, BERLIN))
        // West of UTC the UTC date is already the next day; reading it back still gives the 30th.
        val newYork = ZoneId.of("America/New_York")
        val rule = RepeatRules.toRRule(spec, friday, false, newYork)
        assertEquals(LocalDate.of(2026, 11, 30), RepeatRules.fromRRule(rule, friday, newYork).until)
    }

    @Test
    fun countEnd() {
        val (rule, back) = roundTrip(RepeatSpec(RepeatKind.YEARLY, end = RepeatEnd.COUNT, count = 5))
        assertEquals("FREQ=YEARLY;COUNT=5", rule)
        assertEquals(RepeatEnd.COUNT, back.end)
        assertEquals(5, back.count)
    }

    @Test
    fun rulesBeyondTheEditorAreKeptUnchanged() {
        val imported = "FREQ=MONTHLY;BYMONTHDAY=1,15"
        val spec = RepeatRules.fromRRule(imported, friday, BERLIN)
        assertEquals(RepeatKind.OTHER, spec.kind)
        assertEquals(imported, RepeatRules.toRRule(spec, friday, false, BERLIN))
    }

    @Test
    fun lastDayOfTheMonthRoundTrips() {
        val (rule, back) = roundTrip(RepeatSpec(RepeatKind.MONTHLY_LAST_DAY), start = LocalDate.of(2026, 1, 31))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", rule)
        assertEquals(RepeatKind.MONTHLY_LAST_DAY, back.kind)
    }
}
