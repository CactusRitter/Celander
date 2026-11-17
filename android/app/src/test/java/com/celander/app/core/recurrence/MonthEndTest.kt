package com.celander.app.core.recurrence

import com.celander.app.testing.BERLIN
import com.celander.app.testing.PERSONAL
import com.celander.app.testing.allDayEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Month ends, leap days and year changes in monthly and yearly series (PLAN.md, edge cases). */
class MonthEndTest {
    private fun dates(rrule: String, first: LocalDate, from: LocalDate, to: LocalDate) = OccurrenceCalculator.expand(
        listOf(allDayEvent(1, first, rrule = rrule)),
        emptyMap(),
        mapOf(PERSONAL.id to PERSONAL),
        from,
        to,
        BERLIN,
    ).map { it.timing.startDate(BERLIN) }.sorted()

    @Test
    fun monthlyOnThe31stSkipsShortMonthsLikeOtherCalendars() {
        val result = dates("FREQ=MONTHLY", LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31)), result)
    }

    @Test
    fun lastDayOfTheMonthIncludesFebruary() {
        val result = dates("FREQ=MONTHLY;BYMONTHDAY=-1", LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1))
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)), result)
    }

    @Test
    fun leapDayRepeatsOnlyInLeapYears() {
        val result = dates("FREQ=YEARLY", LocalDate.of(2024, 2, 29), LocalDate.of(2024, 1, 1), LocalDate.of(2033, 1, 1))
        assertEquals(listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29), LocalDate.of(2032, 2, 29)), result)
    }

    @Test
    fun weeklySeriesRunsAcrossTheYearChange() {
        val result = dates("FREQ=WEEKLY", LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 10))
        assertEquals(listOf(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 7)), result)
    }
}
