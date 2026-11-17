package com.celander.app.core.recurrence

import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Edits of a series at an occurrence that was already changed on its own. */
class SeriesExceptionEditsTest {
    private fun at(dateTime: LocalDateTime) = dateTime.atZone(BERLIN).toInstant()

    private fun timed(start: LocalDateTime, minutes: Long) = EventTiming.Timed(at(start), at(start).plus(Duration.ofMinutes(minutes)), BERLIN)

    // Daily stand-up at 9:00; on 21 October it was moved to 11:00 and got its own room.
    private val master = timedEvent(1, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=DAILY").copy(title = "Stand-up", location = "Room A")
    private val key = at(LocalDateTime.of(2026, 10, 21, 9, 0)).toEpochMilli()
    private val moved = EventException(id = 7, eventId = 1, originalStart = key, location = "Room B", timing = timed(LocalDateTime.of(2026, 10, 21, 11, 0), 60))
    private val otherKey = at(LocalDateTime.of(2026, 10, 23, 9, 0)).toEpochMilli()
    private val other = EventException(id = 8, eventId = 1, originalStart = otherKey, title = "Retro")

    /** What the editor shows for the moved occurrence. */
    private val shown = master.copy(location = "Room B", timing = moved.timing!!)

    @Test
    fun thisOnlyStoresWhatDiffersFromTheSeries() {
        val exception = SeriesEdits.exceptionAt(master, key, master.copy(title = "Daily", timing = OccurrenceCalculator.timingAt(master.timing, key)))!!
        assertEquals("Daily", exception.title)
        assertNull(exception.location)
        assertNull(exception.description)
        assertNull(exception.timing)
    }

    @Test
    fun thisOnlyWithoutDifferencesLeavesNoException() {
        assertNull(SeriesEdits.exceptionAt(master, key, master.copy(timing = OccurrenceCalculator.timingAt(master.timing, key))))
    }

    @Test
    fun allAtAMovedOccurrenceKeepsTheSeriesTime() {
        val (updated, kept) = SeriesEdits.applyToAll(master, key, shown.copy(title = "Daily"), listOf(moved, other))
        assertEquals(master.timing, updated.timing)
        assertEquals("Daily", updated.title)
        // The room was the occurrence's own; the series keeps its room.
        assertEquals("Room A", updated.location)
        assertEquals(listOf(moved, other), kept)
    }

    @Test
    fun allClearsTheOccurrencesOwnVersionOfWhatWasEdited() {
        val retitled = other.copy(timing = timed(LocalDateTime.of(2026, 10, 23, 10, 0), 60))
        val edited = master.copy(title = "Daily", timing = retitled.timing!!)
        val (updated, kept) = SeriesEdits.applyToAll(master, otherKey, edited, listOf(retitled))
        assertEquals("Daily", updated.title)
        val left = kept.single()
        assertNull(left.title)
        assertEquals(retitled.timing, left.timing)
    }

    @Test
    fun allDropsAnExceptionWithNothingLeft() {
        val edited = master.copy(title = "Daily", timing = OccurrenceCalculator.timingAt(master.timing, otherKey))
        val (_, kept) = SeriesEdits.applyToAll(master, otherKey, edited, listOf(other))
        assertEquals(emptyList<EventException>(), kept)
    }

    @Test
    fun allWithANewTimeMovesTheSeriesAsFarAsTheOccurrenceMoved() {
        // Shown at 11:00, moved to 12:00: the series moves by an hour, from 9:00 to 10:00.
        val (updated, kept) = SeriesEdits.applyToAll(master, key, shown.copy(timing = timed(LocalDateTime.of(2026, 10, 21, 12, 0), 60)), listOf(moved, other))
        assertEquals(timed(LocalDateTime.of(2026, 10, 19, 10, 0), 60), updated.timing)
        assertEquals(emptyList<EventException>(), kept)
    }

    @Test
    fun followingWithANewTimeStartsAtTheSeriesTimeMovedAsFar() {
        val split = SeriesEdits.splitAt(master, key, shown.copy(timing = timed(LocalDateTime.of(2026, 10, 21, 12, 0), 60)), listOf(moved, other), occurrencesBefore = 0, newUid = "new")!!
        assertEquals(timed(LocalDateTime.of(2026, 10, 21, 10, 0), 60), split.series.timing)
    }

    @Test
    fun followingFromAMovedOccurrenceStartsAtTheSeriesTime() {
        val split = SeriesEdits.splitAt(master, key, shown.copy(title = "Daily"), listOf(moved, other), occurrencesBefore = 0, newUid = "new")!!
        assertEquals(OccurrenceCalculator.timingAt(master.timing, key), split.series.timing)
        assertEquals("Daily", split.series.title)
        assertEquals("Room A", split.series.location)
        assertEquals(listOf(key, otherKey), split.seriesExceptions.map { it.originalStart })
        assertEquals(moved.timing, split.seriesExceptions.first().timing)
    }

    @Test
    fun aNewRuleDropsExceptionsItNoLongerProduces() {
        // Every weekday becomes Mondays only: the change on Friday 23 October would be a stray event.
        val monday = at(LocalDateTime.of(2026, 10, 26, 9, 0)).toEpochMilli()
        val onMonday = EventException(eventId = 1, originalStart = monday, title = "Planning")
        val edited = master.copy(title = "Planning", rrule = "FREQ=WEEKLY;BYDAY=MO", timing = OccurrenceCalculator.timingAt(master.timing, monday))
        val (_, kept) = SeriesEdits.applyToAll(master, monday, edited, listOf(other, onMonday))
        assertEquals(listOf(monday), kept.map { it.originalStart })
    }
    @Test
    fun aRuleRewrittenToFollowTheStartStillSplitsTheCount() {
        // A series from Google with an explicit weekday; the 6th Monday moves to Tuesday, and the editor
        // rewrites the rule to follow the start ("FREQ=WEEKLY;COUNT=10;WKST=SU", no BYDAY).
        val google = timedEvent(2, LocalDateTime.of(2026, 10, 5, 9, 0), 60, rrule = "FREQ=WEEKLY;WKST=SU;COUNT=10;BYDAY=MO")
        val sixth = at(LocalDateTime.of(2026, 11, 9, 9, 0)).toEpochMilli()
        val edited = google.copy(rrule = "FREQ=WEEKLY;COUNT=10;WKST=SU", timing = timed(LocalDateTime.of(2026, 11, 10, 9, 0), 60))
        val split = SeriesEdits.splitAt(google, sixth, edited, emptyList(), SeriesEdits.occurrencesBefore(google, sixth), "new")!!
        assertEquals(5, RRule.parse(split.series.rrule!!)!!.count)
    }

    @Test
    fun anAllDayChangeTurnedBackIntoTheSeriesTimeKeepsTheSeries() {
        val allDay = EventException(id = 9, eventId = 1, originalStart = key, timing = EventTiming.AllDay(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21)))
        val edited = master.copy(timing = timed(LocalDateTime.of(2026, 10, 21, 9, 0), 60))

        val (updated, kept) = SeriesEdits.applyToAll(master, key, edited, listOf(allDay, other))
        assertEquals(master.timing, updated.timing)
        assertEquals(listOf(other), kept)

        val split = SeriesEdits.splitAt(master, key, edited, listOf(allDay, other), occurrencesBefore = 0, newUid = "new")!!
        assertEquals(timed(LocalDateTime.of(2026, 10, 21, 9, 0), 60), split.series.timing)
        assertEquals(listOf(otherKey), split.seriesExceptions.map { it.originalStart })
    }

    @Test
    fun aNewRuleStartingLaterDropsTheChangeAtTheOldStart() {
        // Wednesdays; the occurrence on 4 November has its own room. From there on: Mondays and Fridays.
        val wednesdays = timedEvent(3, LocalDateTime.of(2026, 10, 21, 9, 0), 60, rrule = "FREQ=WEEKLY")
        val wednesday = at(LocalDateTime.of(2026, 11, 4, 9, 0)).toEpochMilli()
        val aula = EventException(eventId = 3, originalStart = wednesday, location = "Aula")
        val edited = wednesdays.copy(location = "Aula", rrule = "FREQ=WEEKLY;BYDAY=MO,FR", timing = OccurrenceCalculator.timingAt(wednesdays.timing, wednesday))

        val split = SeriesEdits.splitAt(wednesdays, wednesday, edited, listOf(aula), occurrencesBefore = 0, newUid = "new")!!
        assertEquals(timed(LocalDateTime.of(2026, 11, 6, 9, 0), 60), split.series.timing)
        assertEquals(emptyList<EventException>(), split.seriesExceptions)
    }

    @Test
    fun aWeeklyRuleThatEndsBeforeItsFirstDayKeepsTheStart() {
        val wednesday = timedEvent(4, LocalDateTime.of(2026, 10, 28, 10, 0), 60, rrule = "FREQ=WEEKLY;BYDAY=MO,FR;UNTIL=20261029T225959Z")
        assertEquals(wednesday, SeriesEdits.startOnRule(wednesday))
        val longer = wednesday.copy(rrule = "FREQ=WEEKLY;BYDAY=MO,FR;UNTIL=20261106T225959Z")
        assertEquals(timed(LocalDateTime.of(2026, 10, 30, 10, 0), 60), SeriesEdits.startOnRule(longer).timing)
    }

    @Test
    fun exceptionsFarApartSurviveANewEnd() {
        val daily = timedEvent(5, LocalDateTime.of(2020, 1, 6, 9, 0), 60, rrule = "FREQ=DAILY")
        val christmas = EventException(eventId = 5, originalStart = at(LocalDateTime.of(2020, 12, 24, 9, 0)).toEpochMilli(), cancelled = true)
        val renamed = EventException(eventId = 5, originalStart = at(LocalDateTime.of(2026, 10, 21, 9, 0)).toEpochMilli(), title = "Offsite")
        val tapped = at(LocalDateTime.of(2026, 10, 28, 9, 0)).toEpochMilli()
        val edited = daily.copy(rrule = "FREQ=DAILY;UNTIL=20271231T225959Z", timing = OccurrenceCalculator.timingAt(daily.timing, tapped))

        val (_, kept) = SeriesEdits.applyToAll(daily, tapped, edited, listOf(christmas, renamed))
        assertEquals(listOf(christmas, renamed), kept)
    }

    @Test
    fun occurrencesBeforeCountsPastTheUsualCap() {
        val start = LocalDateTime.of(2020, 1, 1, 9, 0)
        val long = timedEvent(6, start, 60, rrule = "FREQ=DAILY;COUNT=3000")
        val key = at(start.plusDays(2499)).toEpochMilli()
        val before = SeriesEdits.occurrencesBefore(long, key)
        assertEquals(2499, before)
        val split = SeriesEdits.splitAt(long, key, long.copy(timing = OccurrenceCalculator.timingAt(long.timing, key)), emptyList(), before, "new")!!
        assertEquals(501, RRule.parse(split.series.rrule!!)!!.count)
    }
}
