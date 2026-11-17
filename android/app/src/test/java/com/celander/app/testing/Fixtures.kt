package com.celander.app.testing

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.Task
import com.celander.app.core.model.TaskItem
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.time.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

val PERSONAL = CalendarInfo(id = 1, name = "Personal", colorKey = "lavender")

fun timedEvent(id: Long, start: LocalDateTime, minutes: Long, rrule: String? = null, zone: ZoneId = BERLIN) = Event(
    id = id,
    uid = "uid-$id",
    calendarId = PERSONAL.id,
    title = "Event $id",
    timing = EventTiming.Timed(start.atZone(zone).toInstant(), start.plusMinutes(minutes).atZone(zone).toInstant(), zone),
    rrule = rrule,
)

fun allDayEvent(id: Long, first: LocalDate, last: LocalDate = first, rrule: String? = null) = Event(
    id = id,
    uid = "uid-$id",
    calendarId = PERSONAL.id,
    title = "Event $id",
    timing = EventTiming.AllDay(first, last),
    rrule = rrule,
)

fun Event.occurrence(): EventOccurrence = OccurrenceCalculator.single(this, PERSONAL)

fun taskItem(id: Long, due: LocalDate?, time: LocalTime? = null) =
    TaskItem(Task(id = id, calendarId = PERSONAL.id, title = "Task $id", dueDate = due, dueTime = time), PERSONAL)

/** Fixed clock for repository tests. */
class FixedTimeSource(private val instant: Instant, private val zone: ZoneId = BERLIN) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zone
}
