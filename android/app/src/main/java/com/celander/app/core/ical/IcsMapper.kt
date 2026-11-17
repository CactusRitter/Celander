package com.celander.app.core.ical

import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.recurrence.RRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/** Maps the app's events to iCalendar components and back. */
object IcsMapper {
    private const val DAY_MS = 86_400_000L

    private fun EventTiming.toIcal(): Triple<Long, Long, Boolean> = when (this) {
        is EventTiming.Timed -> Triple(start.toEpochMilli(), end.toEpochMilli(), false)
        is EventTiming.AllDay -> Triple(
            startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            true,
        )
    }

    private fun EventTiming.zoneId(): String = (this as? EventTiming.Timed)?.zone?.id ?: "UTC"

    /** Occurrence key (millis or epoch day) as iCalendar timestamp. */
    private fun keyToMillis(event: Event, key: Long): Long = if (event.allDay) key * DAY_MS else key

    /** Master VEVENT (with EXDATEs) followed by one VEVENT per changed occurrence. */
    fun toIcal(event: Event, exceptions: List<EventException>): List<ICalEvent> {
        val (start, end, allDay) = event.timing.toIcal()
        val reminder = event.reminders.minOrNull()
        val master = ICalEvent(
            uid = event.uid,
            summary = event.title,
            description = event.description,
            location = event.location,
            start = start,
            end = end,
            allDay = allDay,
            timeZone = event.timing.zoneId(),
            rrule = event.rrule,
            exdates = exceptions.filter { it.cancelled }.map { keyToMillis(event, it.originalStart) },
            reminderMinutes = reminder,
        )
        val overrides = exceptions.filter { !it.cancelled }.map { exception ->
            val timing = exception.timing ?: OccurrenceCalculator.timingAt(event.timing, exception.originalStart)
            val (s, e, a) = timing.toIcal()
            ICalEvent(
                uid = event.uid,
                summary = exception.title ?: event.title,
                description = exception.description ?: event.description,
                location = exception.location ?: event.location,
                start = s,
                end = e,
                allDay = a,
                timeZone = timing.zoneId(),
                recurrenceId = keyToMillis(event, exception.originalStart),
                // RECURRENCE-ID names the series' occurrence, so it is written like the series' start.
                recurrenceAllDay = allDay,
                recurrenceTimeZone = event.timing.zoneId(),
                reminderMinutes = reminder,
            )
        }
        return listOf(master) + overrides
    }

    /**
     * Converts parsed components into events for [calendarId]. Overrides (RECURRENCE-ID) and EXDATEs
     * become exceptions of their master; overrides without a master are imported as single events.
     * A cancelled master is left out together with its overrides.
     */
    fun fromIcal(items: List<ICalEvent>, calendarId: Long): List<Pair<Event, List<EventException>>> {
        val masters = items.filter { it.recurrenceId == null && !it.cancelled }
        val overridesByUid = items.filter { it.recurrenceId != null }.groupBy { it.uid }
        val result = masters.map { item ->
            val event = toEvent(item, calendarId)
            val exceptions = buildList {
                item.exdates.forEach { add(EventException(eventId = 0, originalStart = toKey(item.allDay, it), cancelled = true)) }
                overridesByUid[item.uid].orEmpty().forEach { o ->
                    if (o.cancelled) {
                        add(EventException(eventId = 0, originalStart = overrideKey(item, event, o), cancelled = true))
                    } else {
                        add(
                            EventException(
                                eventId = 0,
                                originalStart = overrideKey(item, event, o),
                                title = o.summary,
                                location = o.location,
                                description = o.description,
                                timing = toTiming(o),
                            ),
                        )
                    }
                }
            }
            event to (if (event.rrule != null) exceptions else emptyList())
        }
        val masterUids = items.filter { it.recurrenceId == null }.map { it.uid }.toSet()
        val orphans = overridesByUid.filterKeys { it !in masterUids }.values.flatten().filter { !it.cancelled }
            .map { toEvent(it.copy(uid = it.uid + "-" + it.recurrenceId), calendarId) to emptyList<EventException>() }
        return result + orphans
    }

    private fun toKey(allDay: Boolean, millis: Long): Long = if (allDay) Math.floorDiv(millis, DAY_MS) else millis

    /**
     * Key of the occurrence of [master] ([event]) that [override] replaces. A RECURRENCE-ID should be
     * written like the series' start. One written as a date for a timed series (or as a time for an
     * all-day series) names a day: earlier versions of the app wrote the UTC date of the occurrence
     * (or UTC midnight of its day), other writers mean the local day. Where the two differ, the one
     * that is an occurrence of the series wins, the earlier versions' if both are.
     */
    private fun overrideKey(master: ICalEvent, event: Event, override: ICalEvent): Long {
        val id = override.recurrenceId!!
        val idAllDay = override.recurrenceAllDay ?: master.allDay
        if (idAllDay == master.allDay) return toKey(master.allDay, id)
        val candidates = (if (idAllDay) timesOnDate(master, id) else datesOfTime(override, id)).distinct()
        if (candidates.size == 1) return candidates.single()
        val rule = event.rrule?.let(RRule::parse) ?: return candidates.first()
        val from = candidates.min()
        val until = candidates.max()
        val keys = OccurrenceCalculator.seriesKeys(
            event,
            rule,
            emptySet(),
            Instant.ofEpochMilli(if (master.allDay) from * DAY_MS else from),
            Instant.ofEpochMilli(if (master.allDay) (until + 1) * DAY_MS else until + 1),
        ).toSet()
        return candidates.firstOrNull { it in keys } ?: candidates.first()
    }

    /**
     * Keys a date RECURRENCE-ID [id] of the timed series [master] may stand for: the occurrence whose
     * UTC date it is (as earlier versions wrote it), then the one on that local day.
     */
    private fun timesOnDate(master: ICalEvent, id: Long): List<Long> {
        val zone = TimeZones.resolve(master.timeZone)
        val timeOfDay = Instant.ofEpochMilli(master.start).atZone(zone).toLocalTime()
        val date = LocalDate.ofEpochDay(Math.floorDiv(id, DAY_MS))
        fun at(day: LocalDate) = day.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
        val utcDate = listOf(date.minusDays(1), date, date.plusDays(1)).map(::at)
            .filter { Math.floorDiv(it, DAY_MS) == date.toEpochDay() }
        return utcDate + at(date)
    }

    /**
     * Days a date-time RECURRENCE-ID [id] of an all-day series may stand for: earlier versions wrote
     * the day's UTC midnight in the zone of [override], so a UTC midnight that is not local midnight
     * stands first for its UTC date; then the local date in that zone.
     */
    private fun datesOfTime(override: ICalEvent, id: Long): List<Long> {
        val local = Instant.ofEpochMilli(id).atZone(TimeZones.resolve(override.recurrenceTimeZone ?: override.timeZone))
        val localDate = local.toLocalDate().toEpochDay()
        val utcMidnight = Math.floorMod(id, DAY_MS) == 0L && local.toLocalTime() != LocalTime.MIDNIGHT
        return if (utcMidnight) listOf(Math.floorDiv(id, DAY_MS), localDate) else listOf(localDate)
    }

    private fun toTiming(item: ICalEvent): EventTiming =
        if (item.allDay) {
            val start = LocalDate.ofEpochDay(Math.floorDiv(item.start, DAY_MS))
            val endExclusive = LocalDate.ofEpochDay(Math.floorDiv(item.end, DAY_MS))
            EventTiming.AllDay(start, maxOf(start, endExclusive.minusDays(1)))
        } else {
            EventTiming.Timed(Instant.ofEpochMilli(item.start), Instant.ofEpochMilli(maxOf(item.start, item.end)), TimeZones.resolve(item.timeZone))
        }

    private fun toEvent(item: ICalEvent, calendarId: Long) = Event(
        uid = item.uid.ifBlank { UUID.randomUUID().toString() },
        calendarId = calendarId,
        title = item.summary,
        location = item.location,
        description = item.description,
        timing = toTiming(item),
        rrule = item.rrule,
        // Minutes after the start ("on the day at 9:00") exist only for all-day events; a timed
        // event's reminder after its start fires at the start.
        reminders = listOfNotNull(item.reminderMinutes?.let { if (item.allDay) it else it.coerceAtLeast(0) }),
    )
}
