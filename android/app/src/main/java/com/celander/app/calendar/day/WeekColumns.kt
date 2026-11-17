package com.celander.app.calendar.day

import androidx.compose.ui.unit.Dp
import com.celander.app.core.time.CalendarMath
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The day view as a week of timeline columns in wide windows: whether a width fits the week, how
 * weeks are numbered for paging, and where times sit on the axis the columns share.
 */
object WeekColumns {
    const val DAYS = 7
    const val MINUTES_PER_DAY = 24 * 60L

    /** Whether a timeline [width] holds seven columns of at least [minColumn]. */
    fun fits(width: Dp, minColumn: Dp): Boolean = width / DAYS >= minColumn

    /** Running number of the week containing [date]; consecutive weeks differ by one. */
    fun index(date: LocalDate, firstDay: DayOfWeek): Long = ChronoUnit.WEEKS.between(origin(firstDay), CalendarMath.weekStart(date, firstDay))

    /** First day of the week with the running number [index]. */
    fun start(index: Long, firstDay: DayOfWeek): LocalDate = origin(firstDay).plusWeeks(index)

    private fun origin(firstDay: DayOfWeek): LocalDate = CalendarMath.weekStart(LocalDate.ofEpochDay(0), firstDay)

    /**
     * Minutes since midnight on the wall clock, clamped to [date]. The columns share one axis, so on
     * a daylight saving change the skipped hour stays empty and the repeated one overlaps, instead
     * of that day's hours sliding against the other columns.
     */
    fun wallMinutes(instant: Instant, date: LocalDate, zone: ZoneId): Long = when {
        instant < CalendarMath.dayStart(date, zone) -> 0
        instant >= CalendarMath.dayEnd(date, zone) -> MINUTES_PER_DAY
        else -> instant.atZone(zone).toLocalTime().toSecondOfDay() / 60L
    }
}
