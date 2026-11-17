package com.celander.app.calendar.day

import androidx.compose.ui.unit.dp
import com.celander.app.core.time.CalendarMath
import com.celander.app.testing.BERLIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class WeekColumnsTest {
    @Test
    fun wideViewsShowTheWeekNarrowOnesOneDay() {
        // View widths after the page margins: portrait and landscape tablet, landscape phone.
        assertTrue(dayViewShowsWeek(768.dp))
        assertTrue(dayViewShowsWeek(1248.dp))
        assertTrue(dayViewShowsWeek(843.dp))
        // Half a landscape tablet in split screen, a phone in portrait.
        assertFalse(dayViewShowsWeek(608.dp))
        assertFalse(dayViewShowsWeek(411.dp))
    }

    @Test
    fun theWeekNeedsSevenColumnsOfTheMinimumWidth() {
        assertTrue(WeekColumns.fits(616.dp, 88.dp))
        assertFalse(WeekColumns.fits(615.dp, 88.dp))
    }

    @Test
    fun daysOfOneWeekShareTheirNumberAndTheNextWeekFollows() {
        for (firstDay in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)) {
            val friday = LocalDate.of(2026, 10, 23)
            val week = WeekColumns.index(friday, firstDay)
            val start = CalendarMath.weekStart(friday, firstDay)
            for (day in 0L until 7L) assertEquals(week, WeekColumns.index(start.plusDays(day), firstDay))
            assertEquals(week + 1, WeekColumns.index(start.plusDays(7), firstDay))
            assertEquals(start, WeekColumns.start(week, firstDay))
        }
    }

    @Test
    fun weeksBefore1970RoundTrip() {
        val date = LocalDate.of(1969, 12, 25)
        for (firstDay in DayOfWeek.entries) {
            assertEquals(CalendarMath.weekStart(date, firstDay), WeekColumns.start(WeekColumns.index(date, firstDay), firstDay))
        }
    }

    @Test
    fun theWallClockKeepsHoursInLineOnDaylightSavingChanges() {
        // Berlin turns the clocks back at 3:00 on 25 October 2026 and forward at 2:00 on 29 March.
        val autumn = LocalDate.of(2026, 10, 25)
        val autumnHalfPastThree = LocalDateTime.of(2026, 10, 25, 3, 30).atZone(BERLIN).toInstant()
        assertEquals(210, WeekColumns.wallMinutes(autumnHalfPastThree, autumn, BERLIN))
        assertEquals(270, CalendarMath.minutesIntoDay(autumnHalfPastThree, autumn, BERLIN))

        val spring = LocalDate.of(2026, 3, 29)
        val springHalfPastThree = LocalDateTime.of(2026, 3, 29, 3, 30).atZone(BERLIN).toInstant()
        assertEquals(210, WeekColumns.wallMinutes(springHalfPastThree, spring, BERLIN))
        assertEquals(150, CalendarMath.minutesIntoDay(springHalfPastThree, spring, BERLIN))
    }

    @Test
    fun wallMinutesStayWithinTheDay() {
        val date = LocalDate.of(2026, 10, 23)
        val evening = LocalDateTime.of(2026, 10, 22, 23, 0).atZone(BERLIN).toInstant()
        val midnight = CalendarMath.dayEnd(date, BERLIN)
        assertEquals(0, WeekColumns.wallMinutes(evening, date, BERLIN))
        assertEquals(WeekColumns.MINUTES_PER_DAY, WeekColumns.wallMinutes(midnight, date, BERLIN))
    }
}
