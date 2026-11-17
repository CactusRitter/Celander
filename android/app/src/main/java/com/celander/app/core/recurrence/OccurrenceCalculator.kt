package com.celander.app.core.recurrence

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Turns stored events (series are rules, not rows) into the concrete occurrences of a date window.
 * Cancelled exceptions remove an occurrence; changed ones replace it, even if moved into the window.
 */
object OccurrenceCalculator {
    private const val DAY_MS = 86_400_000L

    fun expand(
        events: List<Event>,
        exceptions: Map<Long, List<EventException>>,
        calendars: Map<Long, CalendarInfo>,
        start: LocalDate,
        endExclusive: LocalDate,
        zone: ZoneId,
    ): List<EventOccurrence> {
        val windowStart = start.atStartOfDay(zone).toInstant()
        val windowEnd = endExclusive.atStartOfDay(zone).toInstant()
        val result = mutableListOf<EventOccurrence>()
        for (event in events) {
            val calendar = calendars[event.calendarId] ?: continue
            val rule = event.rrule?.let(RRule::parse)
            if (rule == null) {
                val occurrence = single(event, calendar)
                if (overlaps(occurrence.timing, windowStart, windowEnd, zone)) result += occurrence
                continue
            }
            val eventExceptions = exceptions[event.id].orEmpty()
            val byKey = eventExceptions.associateBy { it.originalStart }
            for (key in seriesKeys(event, rule, byKey.keys, windowStart, windowEnd)) {
                val occurrence = occurrence(event, calendar, key, null)
                if (overlaps(occurrence.timing, windowStart, windowEnd, zone)) result += occurrence
            }
            for (exception in eventExceptions) {
                if (exception.cancelled) continue
                val occurrence = occurrence(event, calendar, exception.originalStart, exception)
                if (overlaps(occurrence.timing, windowStart, windowEnd, zone)) result += occurrence
            }
        }
        return result
    }

    /**
     * All occurrence keys of a series between two instants (used for reminders and edits), at most
     * [limit] of them.
     */
    fun seriesKeys(
        event: Event,
        rule: RRule,
        excluded: Set<Long>,
        from: Instant,
        until: Instant,
        limit: Int = RecurrenceExpander.MAX_INSTANCES,
    ): List<Long> =
        when (val timing = event.timing) {
            is EventTiming.Timed -> {
                val duration = Duration.between(timing.start, timing.end).toMillis()
                RecurrenceExpander.expand(
                    start = timing.start.toEpochMilli(),
                    duration = duration,
                    zone = timing.zone,
                    allDay = false,
                    rule = rule,
                    exdates = excluded,
                    rangeStart = from.toEpochMilli() - DAY_MS,
                    rangeEnd = until.toEpochMilli() + DAY_MS,
                    limit = limit,
                )
            }
            is EventTiming.AllDay -> {
                val days = ChronoUnit.DAYS.between(timing.startDate, timing.endDate) + 1
                RecurrenceExpander.expand(
                    start = timing.startDate.toEpochDay() * DAY_MS,
                    duration = days * DAY_MS,
                    zone = ZoneOffset.UTC,
                    allDay = true,
                    rule = rule,
                    exdates = excluded.map { it * DAY_MS }.toSet(),
                    rangeStart = from.toEpochMilli() - 2 * DAY_MS,
                    rangeEnd = until.toEpochMilli() + 2 * DAY_MS,
                    limit = limit,
                ).map { it / DAY_MS }
            }
        }

    fun single(event: Event, calendar: CalendarInfo): EventOccurrence = EventOccurrence(
        event = event,
        calendar = calendar,
        originalStart = event.timing.occurrenceKey,
        title = event.title,
        location = event.location,
        description = event.description,
        timing = event.timing,
        isException = false,
    )

    /** The occurrence of [event] at [key], with the changes of [exception] applied. */
    fun occurrence(event: Event, calendar: CalendarInfo, key: Long, exception: EventException?): EventOccurrence {
        val timing = exception?.timing ?: timingAt(event.timing, key)
        return EventOccurrence(
            event = event,
            calendar = calendar,
            originalStart = key,
            title = exception?.title ?: event.title,
            location = exception?.location ?: event.location,
            description = exception?.description ?: event.description,
            timing = timing,
            isException = exception != null,
        )
    }

    /** The series' timing moved to the occurrence with [key], keeping its duration. */
    fun timingAt(master: EventTiming, key: Long): EventTiming = when (master) {
        is EventTiming.Timed -> {
            val start = Instant.ofEpochMilli(key)
            EventTiming.Timed(start, start.plus(Duration.between(master.start, master.end)), master.zone)
        }
        is EventTiming.AllDay -> {
            val date = LocalDate.ofEpochDay(key)
            EventTiming.AllDay(date, date.plusDays(ChronoUnit.DAYS.between(master.startDate, master.endDate)))
        }
    }

    fun overlaps(timing: EventTiming, windowStart: Instant, windowEnd: Instant, zone: ZoneId): Boolean {
        val s = timing.startInstant(zone)
        val e = timing.endInstant(zone)
        return s.isBefore(windowEnd) && (e.isAfter(windowStart) || (s == e && !s.isBefore(windowStart)))
    }
}
