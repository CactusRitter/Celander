package com.celander.app.event

import android.content.res.Resources
import com.celander.app.R
import com.celander.app.core.model.EventTiming
import com.celander.app.core.recurrence.Frequency
import com.celander.app.core.recurrence.RRule
import com.celander.app.core.ui.Formats
import java.time.ZoneId

/** Human-readable description of a recurrence rule, e.g. "Weekly on Monday, Wednesday · until …". */
object RecurrenceText {
    fun describe(resources: Resources, rrule: String, timing: EventTiming, formats: Formats, zone: ZoneId): String {
        val rule = RRule.parse(rrule) ?: return resources.getString(R.string.repeat_custom)
        val start = timing.startDate(zone)
        val n = rule.interval
        // Parts not spelled out below would make the description wrong, so such rules stay "custom".
        val plain = rule.extras.isEmpty() && rule.bySetPos.isEmpty() && rule.byMonth.all { it == start.monthValue }
        val base = when (if (plain) rule.freq else null) {
            Frequency.DAILY -> if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) {
                resources.getQuantityString(R.plurals.repeat_every_days, n, n)
            } else {
                resources.getString(R.string.repeat_custom)
            }
            Frequency.WEEKLY -> {
                val days = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }.sortedBy { it.value }
                resources.getString(
                    R.string.repeat_on,
                    resources.getQuantityString(R.plurals.repeat_every_weeks, n, n),
                    days.joinToString(", ") { formats.weekdayLong(it) },
                )
            }
            Frequency.MONTHLY -> if (rule.byDay.isNotEmpty() && rule.byMonthDay.isNotEmpty()) {
                resources.getString(R.string.repeat_custom)
            } else {
                val head = resources.getQuantityString(R.plurals.repeat_every_months, n, n)
                val detail = when {
                    rule.byDay.isNotEmpty() -> rule.byDay.joinToString(", ") { ordinal(resources, it.ordinal) + " " + formats.weekdayLong(it.day) }
                    rule.byMonthDay.isNotEmpty() -> rule.byMonthDay.joinToString(", ") { day ->
                        if (day > 0) resources.getString(R.string.repeat_day_of_month, day) else resources.getString(R.string.repeat_last_day)
                    }
                    else -> resources.getString(R.string.repeat_day_of_month, start.dayOfMonth)
                }
                resources.getString(R.string.repeat_on, head, detail)
            }
            // BYMONTHDAY without BYMONTH repeats every month; with the start's month and day it is yearly.
            Frequency.YEARLY -> if (rule.byDay.isEmpty() && (rule.byMonthDay.isEmpty() || rule.byMonth.isNotEmpty() && rule.byMonthDay == listOf(start.dayOfMonth))) {
                resources.getString(R.string.repeat_on, resources.getQuantityString(R.plurals.repeat_every_years, n, n), start.format(formats.dayMonth))
            } else {
                resources.getString(R.string.repeat_custom)
            }
            else -> resources.getString(R.string.repeat_custom)
        }
        val end = when {
            rule.count != null -> resources.getQuantityString(R.plurals.repeat_times, rule.count, rule.count)
            rule.until != null -> RepeatRules.untilDate(rule.until, zone)?.let { resources.getString(R.string.repeat_until, it.format(formats.dateMedium)) }
            else -> null
        }
        return listOfNotNull(base, end).joinToString(" · ")
    }

    private fun ordinal(resources: Resources, n: Int): String = when (n) {
        0 -> resources.getString(R.string.repeat_every_weekday_prefix)
        -1 -> resources.getString(R.string.repeat_last)
        in 1..5 -> resources.getStringArray(R.array.repeat_ordinals)[n - 1]
        else -> resources.getString(R.string.repeat_nth, n)
    }
}
