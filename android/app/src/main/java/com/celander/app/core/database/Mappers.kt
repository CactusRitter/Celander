package com.celander.app.core.database

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

fun CalendarEntity.toModel() = CalendarInfo(
    id = id,
    name = name,
    colorKey = colorKey,
    visible = visible,
    enabled = enabled,
    isSample = isSample,
    source = runCatching { CalendarSource.valueOf(source) }.getOrDefault(CalendarSource.LOCAL),
    reminderDefaults = reminderDefaultsOf(eventReminders, allDayReminders, taskReminders),
)

/** The stored per-calendar reminder columns as one value; null while none is set. */
private fun reminderDefaultsOf(event: List<Int>?, allDay: List<Int>?, task: List<Int>?): ReminderDefaults? =
    if (event == null && allDay == null && task == null) null else ReminderDefaults(event.orEmpty(), allDay.orEmpty(), task.orEmpty())

fun CalendarEntity.withReminderDefaults(defaults: ReminderDefaults?) =
    copy(eventReminders = defaults?.event, allDayReminders = defaults?.allDay, taskReminders = defaults?.task)


private fun timing(allDay: Boolean, startUtc: Long?, endUtc: Long?, startDay: Long?, endDay: Long?, zone: ZoneId): EventTiming? =
    if (allDay) {
        if (startDay == null || endDay == null) null else EventTiming.AllDay(LocalDate.ofEpochDay(startDay), LocalDate.ofEpochDay(endDay))
    } else {
        if (startUtc == null || endUtc == null) null else EventTiming.Timed(Instant.ofEpochMilli(startUtc), Instant.ofEpochMilli(endUtc), zone)
    }

private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

fun EventEntity.toModel(): Event = Event(
    id = id,
    uid = uid,
    calendarId = calendarId,
    title = title,
    location = location,
    description = description,
    colorKey = colorKey,
    timing = checkNotNull(timing(allDay, startUtc, endUtc, startDay, endDay, zoneOf(timeZone))) { "Event $id has no start/end" },
    rrule = rrule,
    reminders = reminders,
    isSample = isSample,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Event.toEntity(): EventEntity {
    val timed = timing as? EventTiming.Timed
    val allDayTiming = timing as? EventTiming.AllDay
    return EventEntity(
        id = id,
        uid = uid,
        calendarId = calendarId,
        title = title,
        location = location,
        description = description,
        colorKey = colorKey,
        allDay = allDayTiming != null,
        startUtc = timed?.start?.toEpochMilli(),
        endUtc = timed?.end?.toEpochMilli(),
        startDay = allDayTiming?.startDate?.toEpochDay(),
        endDay = allDayTiming?.endDate?.toEpochDay(),
        timeZone = timed?.zone?.id ?: ZoneId.systemDefault().id,
        rrule = rrule,
        reminders = reminders,
        isSample = isSample,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun EventExceptionEntity.toModel(masterZone: ZoneId): EventException = EventException(
    id = id,
    eventId = eventId,
    originalStart = originalStart,
    cancelled = cancelled,
    title = title,
    location = location,
    description = description,
    timing = allDay?.let { timing(it, startUtc, endUtc, startDay, endDay, masterZone) },
)

fun EventException.toEntity(): EventExceptionEntity {
    val timed = timing as? EventTiming.Timed
    val allDayTiming = timing as? EventTiming.AllDay
    return EventExceptionEntity(
        id = id,
        eventId = eventId,
        originalStart = originalStart,
        cancelled = cancelled,
        title = title,
        location = location,
        description = description,
        allDay = timing?.let { it is EventTiming.AllDay },
        startUtc = timed?.start?.toEpochMilli(),
        endUtc = timed?.end?.toEpochMilli(),
        startDay = allDayTiming?.startDate?.toEpochDay(),
        endDay = allDayTiming?.endDate?.toEpochDay(),
    )
}

fun TaskEntity.toModel(): Task = Task(
    id = id,
    calendarId = calendarId,
    title = title,
    note = note,
    dueDate = dueDay?.let(LocalDate::ofEpochDay),
    dueTime = dueMinute?.let { LocalTime.of(it / 60, it % 60) },
    reminders = reminders,
    done = done,
    completedAt = completedAt,
    isSample = isSample,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    calendarId = calendarId,
    title = title,
    note = note,
    dueDay = dueDate?.toEpochDay(),
    dueMinute = dueTime?.let { it.hour * 60 + it.minute },
    reminders = reminders,
    done = done,
    completedAt = completedAt,
    isSample = isSample,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
