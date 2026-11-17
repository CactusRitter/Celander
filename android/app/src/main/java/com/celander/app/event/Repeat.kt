package com.celander.app.event

import com.celander.app.core.ical.ICalDates
import com.celander.app.core.recurrence.Frequency
import com.celander.app.core.recurrence.RRule
import com.celander.app.core.recurrence.WeekdayNum
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MONTHLY_DATE follows RFC 5545: a start on the 31st skips months without a 31st, like other
 * calendars do. MONTHLY_LAST_DAY ("on the last day") is offered when the start is a month's last day.
 */
enum class RepeatKind { NONE, DAILY, WEEKLY, MONTHLY_DATE, MONTHLY_LAST_DAY, MONTHLY_WEEKDAY, YEARLY, OTHER }

enum class RepeatEnd { NEVER, UNTIL, COUNT }

/**
 * The editor's view of a recurrence rule. Rules it cannot represent (e.g. from an import) are kept
 * as [RepeatKind.OTHER] with the original text, so opening and saving never changes them.
 */
data class RepeatSpec(
    val kind: RepeatKind = RepeatKind.NONE,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val end: RepeatEnd = RepeatEnd.NEVER,
    val until: LocalDate? = null,
    val count: Int = DEFAULT_COUNT,
    val raw: String? = null,
    /** Kept from the rule as it came (WKST), so rewriting it never moves biweekly occurrences. */
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    companion object {
        const val DEFAULT_COUNT = 10
        const val MAX_INTERVAL = 99
        const val MAX_COUNT = 999
    }
}

object RepeatRules {
    private val UNTIL_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    /** n-th weekday of [date] in its month; the last one counts as -1 so it works in every month. */
    fun weekdayOrdinal(date: LocalDate): Int {
        val nth = (date.dayOfMonth - 1) / 7 + 1
        return if (date.dayOfMonth + 7 > date.lengthOfMonth()) -1 else nth
    }

    /** Builds the RRULE for [spec] starting on [start]; null means no repetition. */
    fun toRRule(spec: RepeatSpec, start: LocalDate, allDay: Boolean, zone: ZoneId): String? {
        if (spec.kind == RepeatKind.NONE) return null
        if (spec.kind == RepeatKind.OTHER) return spec.raw
        val interval = spec.interval.coerceIn(1, RepeatSpec.MAX_INTERVAL)
        val base = when (spec.kind) {
            RepeatKind.DAILY -> RRule(Frequency.DAILY, interval)
            RepeatKind.WEEKLY -> {
                val days = spec.weekdays.ifEmpty { setOf(start.dayOfWeek) }
                // Only the start's weekday is the plain weekly rule; it then follows a moved start.
                RRule(Frequency.WEEKLY, interval, byDay = if (days == setOf(start.dayOfWeek)) emptyList() else days.sorted().map { WeekdayNum(0, it) })
            }
            RepeatKind.MONTHLY_DATE -> RRule(Frequency.MONTHLY, interval)
            RepeatKind.MONTHLY_LAST_DAY -> RRule(Frequency.MONTHLY, interval, byMonthDay = listOf(-1))
            RepeatKind.MONTHLY_WEEKDAY -> RRule(Frequency.MONTHLY, interval, byDay = listOf(WeekdayNum(weekdayOrdinal(start), start.dayOfWeek)))
            RepeatKind.YEARLY -> RRule(Frequency.YEARLY, interval)
            RepeatKind.NONE, RepeatKind.OTHER -> return null
        }
        val rule = when (spec.end) {
            RepeatEnd.NEVER -> base.copy(weekStart = spec.weekStart)
            RepeatEnd.COUNT -> base.copy(weekStart = spec.weekStart, count = spec.count.coerceIn(1, RepeatSpec.MAX_COUNT))
            RepeatEnd.UNTIL -> {
                val until = maxOf(spec.until ?: start, start)
                base.copy(
                    weekStart = spec.weekStart,
                    until = if (allDay) {
                        until.format(UNTIL_DATE)
                    } else {
                        // The whole last day counts, in the event's own time zone.
                        ICalDates.formatUtc(until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1_000)
                    },
                )
            }
        }
        return rule.format()
    }

    /** Reads [rrule] back into the editor's model; rules beyond the editor's options stay [RepeatKind.OTHER]. */
    fun fromRRule(rrule: String?, start: LocalDate, zone: ZoneId): RepeatSpec {
        if (rrule == null) return RepeatSpec()
        val rule = RRule.parse(rrule) ?: return RepeatSpec(RepeatKind.OTHER, raw = rrule)
        val other = RepeatSpec(RepeatKind.OTHER, raw = rrule)
        // Parts the editor cannot show (BYHOUR, BYWEEKNO, …) would be lost when it rewrites the rule.
        if (rule.extras.isNotEmpty() || rule.byMonth.isNotEmpty() || rule.bySetPos.isNotEmpty() || rule.byMonthDay.size > 1) return other
        if (rule.count != null && rule.until != null) return other
        // Rewriting would cap these at what the editor offers.
        if (rule.interval > RepeatSpec.MAX_INTERVAL || (rule.count ?: 0) > RepeatSpec.MAX_COUNT) return other
        val kind = when (rule.freq) {
            Frequency.DAILY -> if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) RepeatKind.DAILY else return other
            Frequency.WEEKLY -> if (rule.byDay.all { it.ordinal == 0 } && rule.byMonthDay.isEmpty()) RepeatKind.WEEKLY else return other
            Frequency.MONTHLY -> when {
                rule.byDay.isEmpty() && (rule.byMonthDay.isEmpty() || rule.byMonthDay == listOf(start.dayOfMonth)) -> RepeatKind.MONTHLY_DATE
                rule.byDay.isEmpty() && rule.byMonthDay == listOf(-1) -> RepeatKind.MONTHLY_LAST_DAY
                rule.byMonthDay.isEmpty() && rule.byDay == listOf(WeekdayNum(weekdayOrdinal(start), start.dayOfWeek)) -> RepeatKind.MONTHLY_WEEKDAY
                else -> return other
            }
            Frequency.YEARLY -> if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) RepeatKind.YEARLY else return other
            else -> return other
        }
        val until = rule.until?.let { untilDate(it, zone) }
        return RepeatSpec(
            kind = kind,
            interval = rule.interval,
            weekdays = if (kind == RepeatKind.WEEKLY) rule.byDay.map { it.day }.toSet().ifEmpty { setOf(start.dayOfWeek) } else emptySet(),
            end = when {
                rule.count != null -> RepeatEnd.COUNT
                until != null -> RepeatEnd.UNTIL
                else -> RepeatEnd.NEVER
            },
            until = until,
            count = rule.count ?: RepeatSpec.DEFAULT_COUNT,
            weekStart = rule.weekStart,
        )
    }

    /** The local last day of an UNTIL value (date or UTC date-time). */
    fun untilDate(until: String, zone: ZoneId): LocalDate? = runCatching {
        if (until.length <= 8) {
            LocalDate.parse(until.take(8), UNTIL_DATE)
        } else {
            val utc = ICalDates.parse(until, null, null) ?: return@runCatching null
            Instant.ofEpochMilli(utc.millis).atZone(zone).toLocalDate()
        }
    }.getOrNull()
}
