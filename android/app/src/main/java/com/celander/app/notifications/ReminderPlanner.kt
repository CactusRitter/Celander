package com.celander.app.notifications

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.Task
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.recurrence.RRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

enum class ReminderKind { EVENT, TASK }

/**
 * A reminder that is due at [triggerAt]. [key] is stable per occurrence and lead time and names its
 * notification; [occurrenceStart] is the occurrence key of events (0 for tasks). [firesAt] is when it
 * fires on the clock it follows: the instant for timed events, the local date and time for tasks and
 * all-day events (empty for snoozed reminders and ones read back from a notification).
 */
data class DueReminder(
    val key: String,
    val kind: ReminderKind,
    val refId: Long,
    val occurrenceStart: Long,
    val triggerAt: Instant,
    val firesAt: String = "",
) {
    /**
     * What a shown reminder is remembered as, so it is shown once: its key and when it fires. An
     * occurrence moved on its own keeps its key (its original start) but fires at another time, so it
     * reminds again. The time is the one of the reminder's own clock, so a new time zone changes
     * nothing: a timed event fires at the same instant, a task or all-day event at the same time on
     * the wall clock, which a move west brings again later.
     */
    val handledId: String get() = "$key|$firesAt"
}

/**
 * Computes which reminders fire in a time span. Pure, so the scheduling rules are unit-testable;
 * reminders of hidden calendars fire too (hiding is a matter of the view, see PLAN.md).
 */
object ReminderPlanner {
    /**
     * Reminders that fire in (`after`, `until`], sorted by time, snoozed ones included. Reminders on
     * the wall clock (tasks, all-day events) count from [wallClockAfter] instead, which a move east
     * puts before `after` (see [wallClockShift]).
     */
    fun plan(
        events: List<Event>,
        exceptions: Map<Long, List<EventException>>,
        tasks: List<Task>,
        snoozed: List<DueReminder>,
        after: Instant,
        until: Instant,
        zone: ZoneId,
        wallClockAfter: Instant = after,
    ): List<DueReminder> {
        val result = mutableListOf<DueReminder>()
        // The calculator needs a calendar per event; for reminders any calendar will do.
        val calendars = events.map { it.calendarId }.distinct().associateWith { CalendarInfo(it, "", "") }
        val earliest = minOf(after, wallClockAfter)
        for (event in events) {
            if (event.reminders.isEmpty()) continue
            val leads = event.reminders
            // Occurrences whose start minus a lead time lies inside the span, with a day of margin.
            val from = earliest.plus(Duration.ofMinutes(leads.min().toLong())).minus(Duration.ofDays(1))
            val to = until.plus(Duration.ofMinutes(leads.max().toLong())).plus(Duration.ofDays(1))
            val occurrences = OccurrenceCalculator.expand(
                listOf(event),
                exceptions,
                calendars,
                from.atZone(zone).toLocalDate(),
                to.atZone(zone).toLocalDate().plusDays(1),
                zone,
            )
            for (occurrence in occurrences) {
                for (lead in leads.distinct()) {
                    val (trigger, start, firesAt) = when (val timing = occurrence.timing) {
                        is EventTiming.Timed -> timing.start.minus(Duration.ofMinutes(lead.toLong())).let { Triple(it, after, it.toString()) }
                        // "On the day at 9:00" means 9:00 on the wall clock, also on daylight-saving days.
                        is EventTiming.AllDay -> timing.startDate.atStartOfDay().minusMinutes(lead.toLong())
                            .let { Triple(it.atZone(zone).toInstant(), wallClockAfter, it.toString()) }
                    }
                    if (trigger.isAfter(start) && !trigger.isAfter(until)) {
                        result += DueReminder("e:${event.id}:${occurrence.originalStart}:$lead", ReminderKind.EVENT, event.id, occurrence.originalStart, trigger, firesAt)
                    }
                }
            }
        }
        for (task in tasks) {
            val due = task.dueDate ?: continue
            if (task.done || task.reminders.isEmpty()) continue
            // Due times float with the wall clock, so lead times are counted on it as well.
            val anchor = due.atTime(task.reminderAnchor)
            for (lead in task.reminders.distinct()) {
                val firesAt = anchor.minusMinutes(lead.toLong())
                val trigger = firesAt.atZone(zone).toInstant()
                if (trigger.isAfter(wallClockAfter) && !trigger.isAfter(until)) {
                    // The due date is part of the key, so moving the task lets the reminder fire again.
                    result += DueReminder("t:${task.id}:$anchor:$lead", ReminderKind.TASK, task.id, 0, trigger, firesAt.toString())
                }
            }
        }
        snoozed.filterTo(result) { it.triggerAt.isAfter(after) && !it.triggerAt.isAfter(until) }
        return result.sortedWith(compareBy({ it.triggerAt }, { it.key }))
    }

    /**
     * How far wall-clock reminders moved forward when the zone changed from [previous] to [current]
     * around [at]. Moving east, 9:00 comes earlier than planned, possibly before the moment up to
     * which reminders were handled; moving west it comes later (zero here), and the keys of shown
     * reminders keep it from coming twice.
     */
    fun wallClockShift(previous: ZoneId, current: ZoneId, at: Instant): Duration {
        val seconds = current.rules.getOffset(at).totalSeconds - previous.rules.getOffset(at).totalSeconds
        return Duration.ofSeconds(maxOf(0, seconds).toLong())
    }

    /**
     * Whether [key] still names an occurrence of [event]: one its rule produces or a changed one kept
     * as an exception, but not a cancelled one. "Delete this and following" ends the rule early
     * without leaving exceptions, so the rule itself is asked. Single events keep their reminders.
     */
    fun isOccurrence(event: Event, exceptions: List<EventException>, key: Long): Boolean {
        exceptions.firstOrNull { it.originalStart == key }?.let { return !it.cancelled }
        val rule = event.rrule?.let(RRule::parse) ?: return true
        val at = when (event.timing) {
            is EventTiming.Timed -> Instant.ofEpochMilli(key)
            is EventTiming.AllDay -> LocalDate.ofEpochDay(key).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return key in OccurrenceCalculator.seriesKeys(event, rule, emptySet(), at, at)
    }
}
