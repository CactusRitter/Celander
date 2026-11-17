package com.celander.app.core.recurrence

import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class SeriesEditsShiftTest {
    private fun at(dateTime: LocalDateTime) = dateTime.atZone(BERLIN).toInstant()

    private fun timed(start: LocalDateTime, minutes: Long) = EventTiming.Timed(at(start), at(start).plus(Duration.ofMinutes(minutes)), BERLIN)

    @Test
    fun allEventsMoveByTheSameWallClockShiftAcrossDaylightSaving() {
        // Daily 9:00 from 19 October (summer time); the occurrence on 27 October (winter time) moves to 10:30.
        val master = timedEvent(1, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=DAILY")
        val key = at(LocalDateTime.of(2026, 10, 27, 9, 0)).toEpochMilli()
        val edited = master.copy(title = "Moved", timing = timed(LocalDateTime.of(2026, 10, 27, 10, 30), 30))

        val (updated, _) = SeriesEdits.applyToAll(master, key, edited, emptyList())
        assertEquals(timed(LocalDateTime.of(2026, 10, 19, 10, 30), 30), updated.timing)
        assertEquals("Moved", updated.title)
        assertEquals(master.uid, updated.uid)
    }

    @Test
    fun changedOccurrencesSurviveOnlyWhenTheTimeStays() {
        val master = timedEvent(1, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=DAILY")
        val key = at(LocalDateTime.of(2026, 10, 21, 9, 0)).toEpochMilli()
        val cancelledKey = at(LocalDateTime.of(2026, 10, 23, 9, 0)).toEpochMilli()
        val exceptions = listOf(
            EventException(id = 5, eventId = 1, originalStart = cancelledKey, cancelled = true),
            EventException(id = 6, eventId = 1, originalStart = at(LocalDateTime.of(2026, 10, 24, 9, 0)).toEpochMilli(), title = "Special"),
        )

        val (_, kept) = SeriesEdits.applyToAll(master, key, master.copy(title = "Renamed", timing = OccurrenceCalculator.timingAt(master.timing, key)), exceptions)
        assertEquals(exceptions, kept)

        val (_, moved) = SeriesEdits.applyToAll(master, key, master.copy(timing = timed(LocalDateTime.of(2026, 10, 22, 9, 0), 60)), exceptions)
        // Moved by a day: the cancellation follows, the changed occurrence is dropped.
        assertEquals(listOf(at(LocalDateTime.of(2026, 10, 24, 9, 0)).toEpochMilli()), moved.map { it.originalStart })
        assertEquals(true, moved.single().cancelled)
    }

    @Test
    fun timedSeriesCanBecomeAllDay() {
        val master = timedEvent(1, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=WEEKLY")
        val key = at(LocalDateTime.of(2026, 10, 26, 9, 0)).toEpochMilli()
        val edited = master.copy(timing = EventTiming.AllDay(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26)))
        val (updated, _) = SeriesEdits.applyToAll(master, key, edited, emptyList())
        assertEquals(EventTiming.AllDay(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19)), updated.timing)
    }

    @Test
    fun splitDividesTheCountAndKeepsTheRest() {
        val master = allDayEvent(1, LocalDate.of(2026, 10, 1), rrule = "FREQ=DAILY;COUNT=10")
        val key = LocalDate.of(2026, 10, 4).toEpochDay()
        val exceptions = listOf(
            EventException(eventId = 1, originalStart = LocalDate.of(2026, 10, 2).toEpochDay(), cancelled = true),
            EventException(eventId = 1, originalStart = LocalDate.of(2026, 10, 6).toEpochDay(), cancelled = true),
        )
        val edited = master.copy(title = "From the 4th", timing = EventTiming.AllDay(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4)))

        val split = SeriesEdits.splitAt(master, key, edited, exceptions, occurrencesBefore = 3, newUid = "new")!!
        assertEquals("20261003", RRule.parse(split.master.rrule!!)!!.until)
        assertEquals(listOf(LocalDate.of(2026, 10, 2).toEpochDay()), split.masterExceptions.map { it.originalStart })
        assertEquals(7, RRule.parse(split.series.rrule!!)!!.count)
        assertEquals("new", split.series.uid)
        assertEquals(listOf(LocalDate.of(2026, 10, 6).toEpochDay()), split.seriesExceptions.map { it.originalStart })
    }

    @Test
    fun splittingAtTheFirstOccurrenceIsAnEditOfAll() {
        val master = timedEvent(1, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=DAILY")
        assertNull(SeriesEdits.splitAt(master, master.timing.occurrenceKey, master, emptyList(), 0, "new"))
    }
}
