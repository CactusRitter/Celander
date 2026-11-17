package com.celander.app.core.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/** Date arithmetic for grids, weeks and timelines. Pure java.time, no Android dependencies. */
object CalendarMath {
    const val GRID_WEEKS = 6
    const val GRID_DAYS = GRID_WEEKS * 7

    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay))

    /** First day of the always-six-week month grid, so the grid height never changes. */
    fun monthGridStart(month: YearMonth, firstDay: DayOfWeek): LocalDate = weekStart(month.atDay(1), firstDay)

    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
        val start = monthGridStart(month, firstDay)
        return List(GRID_DAYS) { start.plusDays(it.toLong()) }
    }

    fun weekDays(date: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
        val start = weekStart(date, firstDay)
        return List(7) { start.plusDays(it.toLong()) }
    }

    /** ISO weeks for Monday-start weeks (common in Europe), North American numbering for Sunday starts. */
    fun weekNumber(date: LocalDate, firstDay: DayOfWeek): Int {
        val fields = if (firstDay == DayOfWeek.MONDAY) WeekFields.ISO else WeekFields.of(firstDay, 1)
        return date.get(fields.weekOfWeekBasedYear())
    }

    /** Same day of month in another month, clamped to its last day (31 Jan → 28/29 Feb). */
    fun sameDayIn(month: YearMonth, day: Int): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))

    fun dayStart(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

    fun dayEnd(date: LocalDate, zone: ZoneId): Instant = date.plusDays(1).atStartOfDay(zone).toInstant()

    /** Real length of a day in minutes: 1380 or 1500 on DST switch days, 1440 otherwise. */
    fun dayLengthMinutes(date: LocalDate, zone: ZoneId): Long = Duration.between(dayStart(date, zone), dayEnd(date, zone)).toMinutes()

    data class HourMark(val minuteOffset: Long, val label: LocalTime)

    /**
     * Hour lines of a day's timeline by elapsed time. On DST days the labels skip (spring) or
     * repeat (autumn) an hour, so the timeline has 23 or 25 rows.
     */
    fun hourMarks(date: LocalDate, zone: ZoneId): List<HourMark> {
        val start = dayStart(date, zone)
        val end = dayEnd(date, zone)
        val marks = mutableListOf<HourMark>()
        var t = start
        while (t.isBefore(end)) {
            marks += HourMark(Duration.between(start, t).toMinutes(), t.atZone(zone).toLocalTime())
            t = t.plus(1, ChronoUnit.HOURS)
        }
        return marks
    }

    /** Minutes elapsed since the start of [date] in [zone] (clamped to the day). */
    fun minutesIntoDay(instant: Instant, date: LocalDate, zone: ZoneId): Long {
        val start = dayStart(date, zone)
        val length = dayLengthMinutes(date, zone)
        return Duration.between(start, instant).toMinutes().coerceIn(0, length)
    }

    /** Next full half hour after [now], e.g. 09:10 → 09:30, 09:30 → 10:00. */
    fun nextHalfHour(now: LocalDateTime): LocalDateTime {
        val truncated = now.truncatedTo(ChronoUnit.HOURS)
        val half = truncated.plusMinutes(30)
        return if (now.isBefore(half)) half else truncated.plusHours(1)
    }

    /** Rounds minutes of a day down to the given grid, e.g. 30-minute slots. */
    fun snapMinutes(minutes: Int, grid: Int): Int = (minutes / grid) * grid

    fun monthsBetween(a: YearMonth, b: YearMonth): Long = ChronoUnit.MONTHS.between(a, b)

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
