package com.celander.app.notifications

import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.Task
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlannerTest {
    private fun at(dateTime: LocalDateTime): Instant = dateTime.atZone(BERLIN).toInstant()

    private val after = at(LocalDateTime.of(2026, 10, 23, 8, 0))
    private val until = at(LocalDateTime.of(2026, 10, 26, 0, 0))

    private fun triggers(reminders: List<DueReminder>) = reminders.map { it.triggerAt.atZone(BERLIN).toLocalDateTime() }

    @Test
    fun timedEventsRemindBeforeTheirStart() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 23, 14, 0), 60).copy(reminders = listOf(10, 60))
        val planned = ReminderPlanner.plan(listOf(event), emptyMap(), emptyList(), emptyList(), after, until, BERLIN)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 23, 13, 0), LocalDateTime.of(2026, 10, 23, 13, 50)), triggers(planned))
        assertEquals("e:1:${event.timing.occurrenceKey}:60", planned.first().key)
    }

    @Test
    fun allDayRemindersAreRelativeToMidnight() {
        // 360 minutes before midnight of the 25th: the day before at 18:00. -540: on the day at 9:00.
        val event = allDayEvent(1, LocalDate.of(2026, 10, 25)).copy(reminders = listOf(360, -540))
        val planned = ReminderPlanner.plan(listOf(event), emptyMap(), emptyList(), emptyList(), after, until, BERLIN)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 24, 18, 0), LocalDateTime.of(2026, 10, 25, 9, 0)), triggers(planned))
    }

    @Test
    fun seriesSkipCancelledAndFollowMovedOccurrences() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 20, 9, 0), 30, rrule = "FREQ=DAILY").copy(reminders = listOf(10))
        val cancelled = EventException(eventId = 1, originalStart = at(LocalDateTime.of(2026, 10, 24, 9, 0)).toEpochMilli(), cancelled = true)
        val moved = EventException(
            eventId = 1,
            originalStart = at(LocalDateTime.of(2026, 10, 25, 9, 0)).toEpochMilli(),
            timing = EventTiming.Timed(at(LocalDateTime.of(2026, 10, 25, 11, 0)), at(LocalDateTime.of(2026, 10, 25, 11, 30)), BERLIN),
        )
        val planned = ReminderPlanner.plan(listOf(event), mapOf(1L to listOf(cancelled, moved)), emptyList(), emptyList(), after, until, BERLIN)
        assertEquals(
            listOf(LocalDateTime.of(2026, 10, 23, 8, 50), LocalDateTime.of(2026, 10, 25, 10, 50)),
            triggers(planned),
        )
    }

    @Test
    fun tasksWithoutTimeRemindAtNineAndDoneTasksNever() {
        val tasks = listOf(
            Task(id = 1, calendarId = 1, title = "Plants", dueDate = LocalDate.of(2026, 10, 24), reminders = listOf(0)),
            Task(id = 2, calendarId = 1, title = "Report", dueDate = LocalDate.of(2026, 10, 24), dueTime = LocalTime.of(17, 0), reminders = listOf(30)),
            Task(id = 3, calendarId = 1, title = "Done", dueDate = LocalDate.of(2026, 10, 24), reminders = listOf(0), done = true),
            Task(id = 4, calendarId = 1, title = "Someday", reminders = listOf(0)),
        )
        val planned = ReminderPlanner.plan(emptyList(), emptyMap(), tasks, emptyList(), after, until, BERLIN)
        assertEquals(listOf(1L, 2L), planned.map { it.refId })
        assertEquals(listOf(LocalDateTime.of(2026, 10, 24, 9, 0), LocalDateTime.of(2026, 10, 24, 16, 30)), triggers(planned))
    }

    @Test
    fun spanExcludesItsStartAndIncludesItsEnd() {
        val atStart = DueReminder("s:a", ReminderKind.TASK, 1, 0, after)
        val atEnd = DueReminder("s:b", ReminderKind.TASK, 1, 0, until)
        val planned = ReminderPlanner.plan(emptyList(), emptyMap(), emptyList(), listOf(atStart, atEnd), after, until, BERLIN)
        assertEquals(listOf("s:b"), planned.map { it.key })
    }
}
