package com.celander.app.core.ical

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule

object IcsWriter {
    private const val PRODID = "-//Celander//Celander Android//DE"

    fun calendar(events: List<ICalEvent>, name: String? = null, nowMillis: Long = System.currentTimeMillis(), todos: List<ICalTodo> = emptyList()): String {
        val out = StringBuilder()
        fun line(s: String) = out.append(ContentLine.fold(s)).append("\r\n")

        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:$PRODID")
        line("CALSCALE:GREGORIAN")
        if (name != null) line("X-WR-CALNAME:${ContentLine.escapeText(name)}")

        events.asSequence()
            .flatMap { zonesOf(it).asSequence() }
            .map { TimeZones.resolve(it) }
            .filter { !TimeZones.isUtc(it) }
            .distinctBy { it.id }
            .forEach { zone -> vtimezone(zone).forEach(::line) }

        for (e in events) {
            line("BEGIN:VEVENT")
            line("UID:${e.uid}")
            line("DTSTAMP:${ICalDates.formatUtc(nowMillis)}")
            if (e.recurrenceId != null) {
                val (p, v) = ICalDates.format(e.recurrenceId, e.recurrenceAllDay ?: e.allDay, e.recurrenceTimeZone ?: e.timeZone)
                line("RECURRENCE-ID$p:$v")
            }
            ICalDates.format(e.start, e.allDay, e.timeZone).let { (p, v) -> line("DTSTART$p:$v") }
            ICalDates.format(e.end, e.allDay, e.timeZone).let { (p, v) -> line("DTEND$p:$v") }
            line("SUMMARY:${ContentLine.escapeText(e.summary)}")
            if (e.description.isNotEmpty()) line("DESCRIPTION:${ContentLine.escapeText(e.description)}")
            if (e.location.isNotEmpty()) line("LOCATION:${ContentLine.escapeText(e.location)}")
            if (e.rrule != null) line("RRULE:${e.rrule}")
            if (e.exdates.isNotEmpty()) {
                val formatted = e.exdates.sorted().map { ICalDates.format(it, e.allDay, e.timeZone) }
                line("EXDATE${formatted.first().first}:${formatted.joinToString(",") { it.second }}")
            }
            if (e.sequence > 0) line("SEQUENCE:${e.sequence}")
            if (e.cancelled) line("STATUS:CANCELLED")
            e.extra?.split('\n')?.filter { it.isNotBlank() }?.forEach(::line)
            val reminder = e.reminderMinutes
            if (reminder != null) {
                line("BEGIN:VALARM")
                line("ACTION:DISPLAY")
                line("DESCRIPTION:${ContentLine.escapeText(e.summary.ifBlank { "Reminder" })}")
                // Negative minutes lie after the start: "at 9:00 on the day" of an all-day event is PT540M.
                line(
                    when {
                        reminder > 0 -> "TRIGGER:-PT${reminder}M"
                        reminder < 0 -> "TRIGGER:PT${-reminder}M"
                        else -> "TRIGGER:PT0S"
                    },
                )
                line("END:VALARM")
            }
            line("END:VEVENT")
        }
        for (t in todos) {
            line("BEGIN:VTODO")
            line("UID:${t.uid}")
            line("DTSTAMP:${ICalDates.formatUtc(nowMillis)}")
            line("SUMMARY:${ContentLine.escapeText(t.summary)}")
            if (t.description.isNotEmpty()) line("DESCRIPTION:${ContentLine.escapeText(t.description)}")
            // The due moment is the person's wall clock ("floating"): no zone, like in the app.
            t.due?.let { date ->
                if (t.dueTime == null) line("DUE;VALUE=DATE:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}")
                else line("DUE:${date.atTime(t.dueTime).format(LOCAL)}")
            }
            line(if (t.completed) "STATUS:COMPLETED" else "STATUS:NEEDS-ACTION")
            if (t.completed) t.completedAt?.let { line("COMPLETED:${ICalDates.formatUtc(it)}") }
            for (minutes in t.reminders) {
                line("BEGIN:VALARM")
                line("ACTION:DISPLAY")
                line("DESCRIPTION:${ContentLine.escapeText(t.summary.ifBlank { "Reminder" })}")
                line(
                    when {
                        minutes > 0 -> "TRIGGER:-PT${minutes}M"
                        minutes < 0 -> "TRIGGER:PT${-minutes}M"
                        else -> "TRIGGER:PT0S"
                    },
                )
                line("END:VALARM")
            }
            line("END:VTODO")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Zones the DATE-TIME values of [e] are written in; RECURRENCE-ID follows the series. */
    private fun zonesOf(e: ICalEvent): List<String> = buildList {
        if (!e.allDay) add(e.timeZone)
        if (e.recurrenceId != null && !(e.recurrenceAllDay ?: e.allDay)) add(e.recurrenceTimeZone ?: e.timeZone)
    }

    /** Builds a VTIMEZONE from the zone's current transition rules (or its fixed offset). */
    internal fun vtimezone(zone: ZoneId): List<String> {
        val rules = zone.rules
        val lines = mutableListOf("BEGIN:VTIMEZONE", "TZID:${zone.id}")
        val transitionRules = rules.transitionRules
        if (transitionRules.isEmpty()) {
            val offset = rules.getOffset(java.time.Instant.now())
            lines += listOf(
                "BEGIN:STANDARD",
                "TZOFFSETFROM:${offset(offset)}",
                "TZOFFSETTO:${offset(offset)}",
                "DTSTART:19700101T000000",
                "END:STANDARD",
            )
        } else {
            for (rule in transitionRules) {
                val transition = rule.createTransition(1970)
                val daylight = rule.offsetAfter.totalSeconds > rule.standardOffset.totalSeconds
                val kind = if (daylight) "DAYLIGHT" else "STANDARD"
                lines += "BEGIN:$kind"
                lines += "TZOFFSETFROM:${offset(rule.offsetBefore)}"
                lines += "TZOFFSETTO:${offset(rule.offsetAfter)}"
                lines += "DTSTART:${transition.dateTimeBefore.format(LOCAL)}"
                rrule(rule)?.let { lines += "RRULE:$it" }
                lines += "END:$kind"
            }
        }
        lines += "END:VTIMEZONE"
        return lines
    }

    private fun rrule(rule: ZoneOffsetTransitionRule): String? {
        val month = rule.month.value
        val dow = rule.dayOfWeek ?: return "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=${rule.dayOfMonthIndicator}"
        val indicator = rule.dayOfMonthIndicator
        // java.time expresses "last Sunday" as "Sunday on or after the 25th" in 31-day months.
        val ordinal = when {
            indicator < 0 || indicator + 6 == rule.month.maxLength() -> -1
            (indicator - 1) % 7 == 0 -> (indicator - 1) / 7 + 1
            else -> return null
        }
        return "FREQ=YEARLY;BYMONTH=$month;BYDAY=$ordinal${dayCode(dow)}"
    }

    private fun offset(o: ZoneOffset): String {
        val total = o.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        return "%s%02d%02d".format(sign, abs / 3600, (abs % 3600) / 60)
    }

    fun dayCode(day: DayOfWeek): String = day.name.substring(0, 2)
}
