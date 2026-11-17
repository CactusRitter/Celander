package com.celander.app.core.ical

import java.time.Duration
import java.util.UUID

data class ICalEvent(
    val uid: String,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZone: String,
    val rrule: String? = null,
    val exdates: List<Long> = emptyList(),
    val recurrenceId: Long? = null,
    /**
     * How [recurrenceId] is written: like the series' DTSTART (RFC 5545 §3.8.4.4), which may differ
     * from this instance's own start, e.g. an occurrence of a timed series made all-day. Null means
     * like this instance's [allDay] and [timeZone].
     */
    val recurrenceAllDay: Boolean? = null,
    val recurrenceTimeZone: String? = null,
    val cancelled: Boolean = false,
    val reminderMinutes: Int? = null,
    val sequence: Int = 0,
    val extra: String? = null,
)

data class ICalCalendar(val name: String?, val events: List<ICalEvent>)

object IcsParser {
    /** Properties mapped to [ICalEvent] fields; everything else is preserved in [ICalEvent.extra]. */
    private val MANAGED = setOf(
        "UID", "DTSTART", "DTEND", "DURATION", "SUMMARY", "DESCRIPTION", "LOCATION", "RRULE",
        "EXDATE", "RECURRENCE-ID", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE", "STATUS", "CREATED",
    )
    private const val DAY = 86_400_000L

    fun parse(text: String): List<ICalEvent> = parseCalendar(text).events

    fun parseCalendar(text: String): ICalCalendar {
        val lines = ContentLine.unfold(text)
        val events = mutableListOf<ICalEvent>()
        var name: String? = null
        var current: MutableList<String>? = null
        var depth = 0
        for (line in lines) {
            val upper = line.trim().uppercase()
            val event = current
            if (event == null) {
                when {
                    upper == "BEGIN:VEVENT" -> {
                        current = mutableListOf()
                        depth = 0
                    }
                    upper.startsWith("X-WR-CALNAME") && name == null ->
                        name = ContentLine.parse(line)?.value?.let(ContentLine::unescapeText)
                }
                continue
            }
            when {
                upper == "END:VEVENT" && depth == 0 -> {
                    parseEvent(event)?.let(events::add)
                    current = null
                }
                upper.startsWith("BEGIN:") -> {
                    depth++
                    event += line
                }
                upper.startsWith("END:") -> {
                    depth--
                    event += line
                }
                else -> event += line
            }
        }
        return ICalCalendar(name, events)
    }

    private fun parseEvent(lines: List<String>): ICalEvent? {
        val props = mutableListOf<Pair<ContentLine, String>>()
        val components = mutableListOf<List<String>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().uppercase().startsWith("BEGIN:")) {
                val block = mutableListOf<String>()
                var depth = 0
                while (i < lines.size) {
                    val upper = lines[i].trim().uppercase()
                    if (upper.startsWith("BEGIN:")) depth++
                    if (upper.startsWith("END:")) depth--
                    block += lines[i]
                    i++
                    if (depth == 0) break
                }
                components += block
                continue
            }
            ContentLine.parse(lines[i])?.let { props += it to lines[i] }
            i++
        }

        fun first(name: String) = props.firstOrNull { it.first.name == name }?.first
        fun text(name: String) = first(name)?.value?.let(ContentLine::unescapeText).orEmpty()

        val dtStart = first("DTSTART")?.let(ICalDates::parse) ?: return null
        var end = first("DTEND")?.let(ICalDates::parse)?.millis
            ?: first("DURATION")?.value?.let { ICalDates.plusDuration(dtStart, it) }
            ?: (if (dtStart.allDay) dtStart.millis + DAY else dtStart.millis)
        if (end < dtStart.millis || (dtStart.allDay && end == dtStart.millis)) {
            end = dtStart.millis + if (dtStart.allDay) DAY else 0
        }

        val summary = text("SUMMARY")
        val uid = first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.nameUUIDFromBytes("$summary|${dtStart.millis}".toByteArray()).toString()

        val exdates = props.filter { it.first.name == "EXDATE" }
            .flatMap { ICalDates.parseList(it.first) }
            .map { it.millis }

        val recurrenceId = first("RECURRENCE-ID")?.let(ICalDates::parse)
        val (reminder, alarmBlock) = findReminder(components)
        val extraLines = props.filter { it.first.name !in MANAGED }.map { it.second } +
            components.filter { it !== alarmBlock }.flatten()

        return ICalEvent(
            uid = uid,
            summary = summary,
            description = text("DESCRIPTION"),
            location = text("LOCATION"),
            start = dtStart.millis,
            end = end,
            allDay = dtStart.allDay,
            timeZone = dtStart.zone.id,
            rrule = first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() },
            exdates = exdates,
            recurrenceId = recurrenceId?.millis,
            recurrenceAllDay = recurrenceId?.allDay,
            recurrenceTimeZone = recurrenceId?.zone?.id,
            cancelled = first("STATUS")?.value.equals("CANCELLED", ignoreCase = true),
            reminderMinutes = reminder,
            sequence = first("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0,
            extra = extraLines.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    /**
     * First VALARM with a trigger relative to the start, as minutes before the event. A trigger after
     * the start gives negative minutes: on an all-day event, `PT540M` is 9:00 on the day.
     */
    private fun findReminder(components: List<List<String>>): Pair<Int?, List<String>?> {
        for (block in components) {
            if (!block.first().trim().equals("BEGIN:VALARM", ignoreCase = true)) continue
            val trigger = block.mapNotNull(ContentLine::parse).firstOrNull { it.name == "TRIGGER" } ?: continue
            if (trigger.param("VALUE").equals("DATE-TIME", ignoreCase = true)) continue
            if (trigger.param("RELATED").equals("END", ignoreCase = true)) continue
            val duration: Duration = ICalDates.parseDuration(trigger.value) ?: continue
            return (-duration.toMinutes()).toInt() to block
        }
        return null to null
    }
}
