package com.celander.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class CalendarView { YEAR, MONTH, DAY, AGENDA }

/** Where a calendar's entries live. */
enum class CalendarSource {
    /** Celander's own database. */
    LOCAL,

    /** A Google Tasks list mirrored into Celander's database. */
    GOOGLE_TASKS,

    /** A calendar of an account or feed (Google, Microsoft, an .ics link): its events are kept in Celander's database and synced. */
    ACCOUNT,
}

enum class EntryKind { EVENT, TASK }

data class CalendarInfo(
    val id: Long,
    val name: String,
    val colorKey: String,
    val visible: Boolean = true,
    /** The "Manage calendars" switch; a disabled calendar is out of the drawer, views, search and defaults. */
    val enabled: Boolean = true,
    val isSample: Boolean = false,
    val source: CalendarSource = CalendarSource.LOCAL,
    val writable: Boolean = true,
    val account: String? = null,
    val accountType: String? = null,
    val maxReminders: Int? = null,
    /** The calendar's own reminders for new entries; null uses the app-wide defaults. */
    val reminderDefaults: ReminderDefaults? = null,
) {
    /** Google Tasks lists hold tasks only, local calendars both, account calendars events only. */
    val holdsEvents: Boolean get() = source != CalendarSource.GOOGLE_TASKS
    val holdsTasks: Boolean get() = source == CalendarSource.LOCAL || source == CalendarSource.GOOGLE_TASKS

    fun canHold(kind: EntryKind): Boolean = writable && if (kind == EntryKind.EVENT) holdsEvents else holdsTasks

    /** Offered for new entries of [kind]: it can hold them and is switched on in "Manage calendars". */
    fun offers(kind: EntryKind): Boolean = enabled && canHold(kind)

    /** Its entries appear in the views: enabled in "Manage calendars" and checked in the drawer. */
    val shown: Boolean get() = enabled && visible
}

/**
 * Reminders a new entry starts with, in minutes before (see [Event.reminders]): one list for timed
 * events, one for all-day events and one for tasks. The app-wide defaults are one of these, and a
 * calendar may have its own.
 */
data class ReminderDefaults(
    val event: List<Int>,
    val allDay: List<Int>,
    val task: List<Int>,
) {
    fun forEntry(kind: EntryKind, allDay: Boolean): List<Int> = when {
        kind == EntryKind.TASK -> task
        allDay -> this.allDay
        else -> event
    }

    companion object {
        /** The calendar's own defaults if it has them, else [global]. */
        fun effective(calendar: CalendarInfo?, global: ReminderDefaults): ReminderDefaults = calendar?.reminderDefaults ?: global
    }
}

/** Why an event cannot be changed in Celander. */
enum class ReadOnlyReason { CALENDAR, GUEST, NO_PERMISSION }

/** What Celander may do with an event; local events allow everything. */
data class EventAccess(
    val canEdit: Boolean = true,
    val canDelete: Boolean = true,
    val reason: ReadOnlyReason? = null,
    val organizer: String? = null,
)

/** Which series edits a calendar can store faithfully; local series support all of them. */
data class SeriesCapabilities(
    val canSplit: Boolean = true,
    val canCap: Boolean = true,
    val canShiftAll: Boolean = true,
)

/**
 * When an event or occurrence takes place. Timed events are instants (stored in UTC) plus the zone
 * whose wall clock recurrences follow; all-day events are plain dates with an inclusive end.
 */
sealed interface EventTiming {
    data class Timed(val start: Instant, val end: Instant, val zone: ZoneId) : EventTiming {
        init {
            require(!end.isBefore(start)) { "end before start" }
        }
    }

    data class AllDay(val startDate: LocalDate, val endDate: LocalDate) : EventTiming {
        init {
            require(!endDate.isBefore(startDate)) { "end before start" }
        }
    }

    /** Key of the occurrence starting with this timing: epoch millis (timed) or epoch day (all-day). */
    val occurrenceKey: Long
        get() = when (this) {
            is Timed -> start.toEpochMilli()
            is AllDay -> startDate.toEpochDay()
        }

    fun startDate(zone: ZoneId): LocalDate = when (this) {
        is Timed -> start.atZone(zone).toLocalDate()
        is AllDay -> startDate
    }

    /** Last calendar day covered; an end exactly at midnight belongs to the previous day. */
    fun lastDate(zone: ZoneId): LocalDate = when (this) {
        is AllDay -> endDate
        is Timed -> {
            val s = start.atZone(zone)
            val e = end.atZone(zone)
            if (e.isAfter(s) && e.toLocalTime() == LocalTime.MIDNIGHT) e.toLocalDate().minusDays(1) else e.toLocalDate()
        }
    }

    fun occursOn(date: LocalDate, zone: ZoneId): Boolean = !date.isBefore(startDate(zone)) && !date.isAfter(lastDate(zone))

    /** Start/end instants in [zone]; all-day ranges cover whole local days. */
    fun startInstant(zone: ZoneId): Instant = when (this) {
        is Timed -> start
        is AllDay -> startDate.atStartOfDay(zone).toInstant()
    }

    fun endInstant(zone: ZoneId): Instant = when (this) {
        is Timed -> end
        is AllDay -> endDate.plusDays(1).atStartOfDay(zone).toInstant()
    }

    fun spansMultipleDays(zone: ZoneId): Boolean = lastDate(zone) != startDate(zone)
}

data class Event(
    val id: Long = 0,
    val uid: String,
    val calendarId: Long,
    val title: String,
    val location: String = "",
    val description: String = "",
    val colorKey: String? = null,
    val timing: EventTiming,
    val rrule: String? = null,
    val reminders: List<Int> = emptyList(),
    val isSample: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isRecurring: Boolean get() = rrule != null
    val allDay: Boolean get() = timing is EventTiming.AllDay
}

/**
 * Deviation of one occurrence of a recurring event, identified by [originalStart] (the occurrence
 * key it replaces). Either [cancelled], or carrying overridden fields; null fields keep the master's.
 */
data class EventException(
    val id: Long = 0,
    val eventId: Long,
    val originalStart: Long,
    val cancelled: Boolean = false,
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val timing: EventTiming? = null,
)

data class Task(
    val id: Long = 0,
    val calendarId: Long,
    val title: String,
    val note: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val reminders: List<Int> = emptyList(),
    val done: Boolean = false,
    val completedAt: Long? = null,
    val isSample: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Reminders of tasks without a time are anchored at this time on the due date. */
    val reminderAnchor: LocalTime get() = dueTime ?: DEFAULT_TASK_ANCHOR

    fun isOverdue(now: ZonedDateTime): Boolean {
        val date = dueDate ?: return false
        if (done) return false
        val today = now.toLocalDate()
        return when {
            date.isBefore(today) -> true
            date.isAfter(today) -> false
            else -> dueTime != null && dueTime.isBefore(now.toLocalTime())
        }
    }

    companion object {
        val DEFAULT_TASK_ANCHOR: LocalTime = LocalTime.of(9, 0)
    }
}

/** One concrete occurrence of an event (a single event yields exactly one). */
data class EventOccurrence(
    val event: Event,
    val calendar: CalendarInfo,
    val originalStart: Long,
    val title: String,
    val location: String,
    val description: String,
    val timing: EventTiming,
    val isException: Boolean,
) {
    val key: String get() = "${event.id}:$originalStart"
    val colorKey: String get() = event.colorKey ?: calendar.colorKey
    val isRecurring: Boolean get() = event.isRecurring
    val allDay: Boolean get() = timing is EventTiming.AllDay
}

/** Task together with its calendar (for the color). */
data class TaskItem(val task: Task, val calendar: CalendarInfo) {
    val colorKey: String get() = calendar.colorKey
}
