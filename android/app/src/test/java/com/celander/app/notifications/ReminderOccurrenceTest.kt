package com.celander.app.notifications

import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.Task
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Planner rules for time zone changes and for occurrences that moved or are gone. */
class ReminderOccurrenceTest {
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun at(dateTime: LocalDateTime): Instant = dateTime.atZone(BERLIN).toInstant()

    private fun key(dateTime: LocalDateTime): Long = at(dateTime).toEpochMilli()

    @Test
    fun onlyAMoveEastBringsWallClockRemindersForward() {
        val noon = at(LocalDateTime.of(2026, 10, 23, 12, 0))
        assertEquals(Duration.ofHours(6), ReminderPlanner.wallClockShift(newYork, BERLIN, noon))
        assertEquals(Duration.ZERO, ReminderPlanner.wallClockShift(BERLIN, newYork, noon))
        assertEquals(Duration.ZERO, ReminderPlanner.wallClockShift(BERLIN, BERLIN, noon))
    }

    @Test
    fun wallClockRemindersCountFromTheirOwnStart() {
        val after = at(LocalDateTime.of(2026, 10, 23, 10, 0))
        val wallClockAfter = at(LocalDateTime.of(2026, 10, 23, 6, 0))
        val until = at(LocalDateTime.of(2026, 10, 23, 12, 0))
        val task = Task(id = 1, calendarId = 1, title = "Call", dueDate = LocalDate.of(2026, 10, 23), dueTime = LocalTime.of(8, 0), reminders = listOf(0))
        // -480: on the day at 8:00.
        val allDay = allDayEvent(2, LocalDate.of(2026, 10, 23)).copy(reminders = listOf(-480))
        val timed = timedEvent(3, LocalDateTime.of(2026, 10, 23, 8, 30), 30).copy(reminders = listOf(0))
        val planned = ReminderPlanner.plan(listOf(allDay, timed), emptyMap(), listOf(task), emptyList(), after, until, BERLIN, wallClockAfter)
        // The timed event is no wall-clock reminder: it was due before `after`.
        assertEquals(listOf(2L, 1L), planned.map { it.refId })
    }

    @Test
    fun aMovedOccurrenceIsANewReminderUnderTheSameKey() {
        val series = timedEvent(1, LocalDateTime.of(2026, 10, 20, 9, 0), 30, rrule = "FREQ=DAILY").copy(reminders = listOf(10))
        val moved = EventException(
            eventId = 1,
            originalStart = key(LocalDateTime.of(2026, 10, 23, 9, 0)),
            timing = EventTiming.Timed(at(LocalDateTime.of(2026, 10, 23, 9, 30)), at(LocalDateTime.of(2026, 10, 23, 10, 0)), BERLIN),
        )
        fun morning(exceptions: List<EventException>) = ReminderPlanner.plan(
            listOf(series), mapOf(1L to exceptions), emptyList(), emptyList(),
            at(LocalDateTime.of(2026, 10, 23, 0, 0)), at(LocalDateTime.of(2026, 10, 23, 12, 0)), BERLIN,
        ).single()
        val planned = morning(emptyList())
        val afterTheMove = morning(listOf(moved))
        // The same notification, but a reminder of its own: it is shown again even if the old one was.
        assertEquals(planned.key, afterTheMove.key)
        assertNotEquals(planned.handledId, afterTheMove.handledId)
    }

    @Test
    fun aNewTimeZoneKeepsWhatAReminderIsHandledAs() {
        val timed = timedEvent(1, LocalDateTime.of(2026, 10, 23, 9, 0), 30).copy(reminders = listOf(10))
        // -540: on the day at 9:00.
        val allDay = allDayEvent(2, LocalDate.of(2026, 10, 23)).copy(reminders = listOf(-540))
        val after = at(LocalDateTime.of(2026, 10, 22, 0, 0))
        val until = at(LocalDateTime.of(2026, 10, 25, 0, 0))
        fun planned(zone: ZoneId) = ReminderPlanner.plan(listOf(timed, allDay), emptyMap(), emptyList(), emptyList(), after, until, zone).associateBy { it.refId }
        val inBerlin = planned(BERLIN)
        val inNewYork = planned(newYork)
        assertEquals(inBerlin.getValue(1L).handledId, inNewYork.getValue(1L).handledId)
        // 9:00 on the wall clock comes six hours later in New York and is still the same reminder.
        assertEquals(Duration.ofHours(6), Duration.between(inBerlin.getValue(2L).triggerAt, inNewYork.getValue(2L).triggerAt))
        assertEquals(inBerlin.getValue(2L).handledId, inNewYork.getValue(2L).handledId)
    }

    @Test
    fun anOccurrencePastTheEndOfTheSeriesIsGone() {
        val series = timedEvent(1, LocalDateTime.of(2026, 10, 20, 9, 0), 30, rrule = "FREQ=DAILY;UNTIL=20261022T235959Z")
        assertTrue(ReminderPlanner.isOccurrence(series, emptyList(), key(LocalDateTime.of(2026, 10, 22, 9, 0))))
        assertFalse(ReminderPlanner.isOccurrence(series, emptyList(), key(LocalDateTime.of(2026, 10, 23, 9, 0))))
        // Not a time the rule produces at all.
        assertFalse(ReminderPlanner.isOccurrence(series, emptyList(), key(LocalDateTime.of(2026, 10, 21, 9, 30))))
    }

    @Test
    fun exceptionsDecideForTheirOccurrence() {
        val series = timedEvent(1, LocalDateTime.of(2026, 10, 20, 9, 0), 30, rrule = "FREQ=DAILY")
        val cancelled = EventException(eventId = 1, originalStart = key(LocalDateTime.of(2026, 10, 21, 9, 0)), cancelled = true)
        val moved = EventException(
            eventId = 1,
            originalStart = key(LocalDateTime.of(2026, 10, 22, 9, 0)),
            timing = EventTiming.Timed(at(LocalDateTime.of(2026, 10, 22, 11, 0)), at(LocalDateTime.of(2026, 10, 22, 11, 30)), BERLIN),
        )
        val exceptions = listOf(cancelled, moved)
        assertFalse(ReminderPlanner.isOccurrence(series, exceptions, cancelled.originalStart))
        assertTrue(ReminderPlanner.isOccurrence(series, exceptions, moved.originalStart))
        assertTrue(ReminderPlanner.isOccurrence(series, exceptions, key(LocalDateTime.of(2026, 10, 23, 9, 0))))
    }

    @Test
    fun allDaySeriesAreKeyedByDay() {
        val series = allDayEvent(1, LocalDate.of(2026, 10, 5), rrule = "FREQ=WEEKLY;COUNT=2")
        assertTrue(ReminderPlanner.isOccurrence(series, emptyList(), LocalDate.of(2026, 10, 12).toEpochDay()))
        assertFalse(ReminderPlanner.isOccurrence(series, emptyList(), LocalDate.of(2026, 10, 19).toEpochDay()))
    }

    @Test
    fun singleEventsKeepTheirReminders() {
        val single = timedEvent(1, LocalDateTime.of(2026, 10, 23, 9, 0), 30)
        assertTrue(ReminderPlanner.isOccurrence(single, emptyList(), single.timing.occurrenceKey))
    }
}
