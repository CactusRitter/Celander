package com.celander.app.core.time

import com.celander.app.testing.BERLIN
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

class CalendarMathTest {
    @Test
    fun monthGridAlwaysHasSixWeeksStartingOnTheFirstDay() {
        for (firstDay in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)) {
            for (month in 1..12) {
                val grid = CalendarMath.monthGrid(YearMonth.of(2026, month), firstDay)
                assertEquals(42, grid.size)
                assertEquals(firstDay, grid.first().dayOfWeek)
            }
        }
        // February 2026 starts on a Sunday: Monday weeks begin in January.
        assertEquals(LocalDate.of(2026, 1, 26), CalendarMath.monthGrid(YearMonth.of(2026, 2), DayOfWeek.MONDAY).first())
    }

    @Test
    fun daylightSavingDaysHave23Or25Hours() {
        val spring = CalendarMath.hourMarks(LocalDate.of(2026, 3, 29), BERLIN)
        assertEquals(23, spring.size)
        assertEquals(LocalTime.of(3, 0), spring[2].label)

        val autumn = CalendarMath.hourMarks(LocalDate.of(2026, 10, 25), BERLIN)
        assertEquals(25, autumn.size)
        assertEquals(LocalTime.of(2, 0), autumn[2].label)
        assertEquals(LocalTime.of(2, 0), autumn[3].label)
        assertEquals(1500L, CalendarMath.dayLengthMinutes(LocalDate.of(2026, 10, 25), BERLIN))
    }

    @Test
    fun isoWeekNumbersForMondayWeeks() {
        assertEquals(1, CalendarMath.weekNumber(LocalDate.of(2026, 1, 1), DayOfWeek.MONDAY))
        assertEquals(53, CalendarMath.weekNumber(LocalDate.of(2026, 12, 31), DayOfWeek.MONDAY))
    }

    @Test
    fun nextHalfHourRoundsUp() {
        assertEquals(LocalDateTime.of(2026, 10, 23, 9, 30), CalendarMath.nextHalfHour(LocalDateTime.of(2026, 10, 23, 9, 10)))
        assertEquals(LocalDateTime.of(2026, 10, 23, 10, 0), CalendarMath.nextHalfHour(LocalDateTime.of(2026, 10, 23, 9, 30)))
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 0), CalendarMath.nextHalfHour(LocalDateTime.of(2026, 10, 23, 23, 45)))
    }

    @Test
    fun sameDayIsClampedToShortMonths() {
        assertEquals(LocalDate.of(2026, 2, 28), CalendarMath.sameDayIn(YearMonth.of(2026, 2), 31))
    }
}
