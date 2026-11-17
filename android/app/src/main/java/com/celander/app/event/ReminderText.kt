package com.celander.app.event

import android.content.res.Resources
import com.celander.app.R
import com.celander.app.core.ui.Formats
import java.time.LocalTime

/**
 * Reminders are minutes before an anchor: the start of timed events, midnight of all-day events and
 * the due time of tasks (09:00 without a time). Negative values lie after the anchor, which all-day
 * events use for "on the day at 9:00".
 */
object ReminderText {
    private const val HOUR = 60
    private const val DAY = 1_440
    private const val WEEK = 10_080

    fun label(resources: Resources, minutes: Int, allDay: Boolean, task: Boolean, formats: Formats): String {
        if (allDay) {
            val dayOffset = Math.floorDiv(-minutes, DAY)
            val time = LocalTime.ofSecondOfDay(Math.floorMod(-minutes, DAY) * 60L).format(formats.time)
            return when (dayOffset) {
                0 -> resources.getString(R.string.reminder_on_day_at, time)
                -1 -> resources.getString(R.string.reminder_day_before_at, time)
                else -> resources.getQuantityString(R.plurals.reminder_days_before_at, -dayOffset, -dayOffset, time)
            }
        }
        return when {
            minutes <= 0 -> resources.getString(if (task) R.string.reminder_at_due else R.string.reminder_at_start)
            minutes % WEEK == 0 -> resources.getQuantityString(R.plurals.reminder_weeks_before, minutes / WEEK, minutes / WEEK)
            minutes % DAY == 0 -> resources.getQuantityString(R.plurals.reminder_days_before, minutes / DAY, minutes / DAY)
            minutes % HOUR == 0 -> resources.getQuantityString(R.plurals.reminder_hours_before, minutes / HOUR, minutes / HOUR)
            else -> resources.getQuantityString(R.plurals.reminder_minutes_before, minutes, minutes)
        }
    }

    /** Offered choices; anything else can be added as a custom reminder. */
    fun presets(allDay: Boolean): List<Int> =
        if (allDay) listOf(-540, 360, 900, 2_340, 9_540) else listOf(0, 5, 10, 15, 30, 60, 120, DAY, 2 * DAY, WEEK)

    /** Custom all-day reminders are whole days before, at 09:00. */
    fun allDayDaysBefore(days: Int): Int = days * DAY - 9 * HOUR

    fun minutes(amount: Int, unit: ReminderUnit): Int = amount * unit.minutes
}

enum class ReminderUnit(val minutes: Int) { MINUTES(1), HOURS(60), DAYS(1_440), WEEKS(10_080) }
