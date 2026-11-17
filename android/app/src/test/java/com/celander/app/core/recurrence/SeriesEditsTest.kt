package com.celander.app.core.recurrence

import com.celander.app.core.model.EventException
import com.celander.app.testing.BERLIN
import com.celander.app.testing.PERSONAL
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SeriesEditsTest {
    private fun keyAt(dateTime: LocalDateTime) = dateTime.atZone(BERLIN).toInstant().toEpochMilli()

    @Test
    fun endingBeforeFirstOccurrenceLeavesNothing() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY")
        assertNull(SeriesEdits.endBefore(event, event.timing.occurrenceKey))
    }

    @Test
    fun countIsReplacedByUntilBeforeTheKey() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY;COUNT=10")
        val shortened = SeriesEdits.endBefore(event, keyAt(LocalDateTime.of(2026, 10, 26, 9, 0)))!!
        val rule = RRule.parse(shortened.rrule!!)!!
        assertNull(rule.count)
        assertTrue(rule.until != null)
        val occurrences = OccurrenceCalculator.expand(
            listOf(shortened),
            emptyMap(),
            mapOf(PERSONAL.id to PERSONAL),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2027, 1, 1),
            BERLIN,
        )
        assertEquals(listOf(5, 12, 19), occurrences.map { it.timing.startDate(BERLIN).dayOfMonth }.sorted())
    }

    @Test
    fun allDaySeriesEndsWithDateUntil() {
        val event = allDayEvent(1, LocalDate.of(2026, 10, 1), rrule = "FREQ=DAILY")
        val shortened = SeriesEdits.endBefore(event, LocalDate.of(2026, 10, 10).toEpochDay())!!
        assertEquals("20261009", RRule.parse(shortened.rrule!!)!!.until)
    }

    @Test
    fun exceptionsAreSplitAtTheKeyAndShifted() {
        val exceptions = listOf(
            EventException(id = 1, eventId = 1, originalStart = 100, cancelled = true),
            EventException(id = 2, eventId = 1, originalStart = 200, title = "Changed"),
        )
        assertEquals(listOf(100L), SeriesEdits.exceptionsBefore(exceptions, 150).map { it.originalStart })
        val moved = SeriesEdits.exceptionsFrom(exceptions, 150, shift = 50)
        assertEquals(listOf(250L), moved.map { it.originalStart })
        assertEquals(0L, moved.single().id)
    }
}
