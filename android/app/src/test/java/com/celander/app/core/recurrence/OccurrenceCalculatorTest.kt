package com.celander.app.core.recurrence

import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import com.celander.app.testing.PERSONAL
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OccurrenceCalculatorTest {
    private val calendars = mapOf(PERSONAL.id to PERSONAL)
    private val october = LocalDate.of(2026, 10, 1)
    private val november = LocalDate.of(2026, 11, 1)

    private fun keyAt(dateTime: LocalDateTime) = dateTime.atZone(BERLIN).toInstant().toEpochMilli()

    @Test
    fun weeklySeriesExpandsInsideWindow() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY;BYDAY=MO,WE")
        val days = OccurrenceCalculator.expand(listOf(event), emptyMap(), calendars, october, november, BERLIN)
            .map { it.timing.startDate(BERLIN) }
            .sorted()
        assertEquals(listOf(5, 7, 12, 14, 19, 21, 26, 28), days.map { it.dayOfMonth })
    }

    @Test
    fun dailySeriesKeepsWallClockAcrossDaylightSavingChange() {
        // Summer time in Berlin ends on 25 October 2026.
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 23, 9, 0), 60, rrule = "FREQ=DAILY;COUNT=5")
        val times = OccurrenceCalculator.expand(listOf(event), emptyMap(), calendars, october, november, BERLIN)
            .map { (it.timing as EventTiming.Timed).start.atZone(BERLIN).toLocalTime() }
        assertEquals(List(5) { LocalTime.of(9, 0) }, times)
    }

    @Test
    fun cancelledExceptionRemovesOccurrence() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY;BYDAY=MO")
        val cancelled = SeriesEdits.cancel(1, keyAt(LocalDateTime.of(2026, 10, 12, 9, 0)))
        val days = OccurrenceCalculator.expand(listOf(event), mapOf(1L to listOf(cancelled)), calendars, october, november, BERLIN)
            .map { it.timing.startDate(BERLIN).dayOfMonth }
            .sorted()
        assertEquals(listOf(5, 19, 26), days)
    }

    @Test
    fun movedExceptionAppearsAtItsNewTimeOnly() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY;BYDAY=MO")
        val key = keyAt(LocalDateTime.of(2026, 10, 12, 9, 0))
        val moved = EventException(
            eventId = 1,
            originalStart = key,
            title = "Moved",
            timing = EventTiming.Timed(
                LocalDateTime.of(2026, 10, 13, 15, 0).atZone(BERLIN).toInstant(),
                LocalDateTime.of(2026, 10, 13, 16, 0).atZone(BERLIN).toInstant(),
                BERLIN,
            ),
        )
        val result = OccurrenceCalculator.expand(listOf(event), mapOf(1L to listOf(moved)), calendars, october, november, BERLIN)
        assertEquals(listOf(5, 13, 19, 26), result.map { it.timing.startDate(BERLIN).dayOfMonth }.sorted())
        val changed = result.single { it.isException }
        assertEquals("Moved", changed.title)
        assertEquals(key, changed.originalStart)
    }

    @Test
    fun exceptionMovedIntoWindowIsIncluded() {
        // The series starts on Wednesday 30 September; its first occurrence moves to 1 October.
        val event = timedEvent(1, LocalDateTime.of(2026, 9, 30, 9, 0), 30, rrule = "FREQ=WEEKLY;COUNT=1")
        val moved = EventException(
            eventId = 1,
            originalStart = keyAt(LocalDateTime.of(2026, 9, 30, 9, 0)),
            timing = EventTiming.Timed(
                LocalDateTime.of(2026, 10, 1, 9, 0).atZone(BERLIN).toInstant(),
                LocalDateTime.of(2026, 10, 1, 9, 30).atZone(BERLIN).toInstant(),
                BERLIN,
            ),
        )
        val result = OccurrenceCalculator.expand(listOf(event), mapOf(1L to listOf(moved)), calendars, october, november, BERLIN)
        assertEquals(listOf(LocalDate.of(2026, 10, 1)), result.map { it.timing.startDate(BERLIN) })
    }

    @Test
    fun multiDayAllDayEventOverlapsWindowStart() {
        val event = allDayEvent(1, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2))
        val result = OccurrenceCalculator.expand(listOf(event), emptyMap(), calendars, october, november, BERLIN)
        assertEquals(1, result.size)
    }

    @Test
    fun eventEndingAtWindowStartIsExcluded() {
        val event = timedEvent(1, LocalDateTime.of(2026, 9, 30, 23, 0), 60)
        val result = OccurrenceCalculator.expand(listOf(event), emptyMap(), calendars, october, november, BERLIN)
        assertTrue(result.isEmpty())
    }

    @Test
    fun timingAtKeepsDuration() {
        val master = allDayEvent(1, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7)).timing
        val moved = OccurrenceCalculator.timingAt(master, LocalDate.of(2026, 10, 12).toEpochDay()) as EventTiming.AllDay
        assertEquals(LocalDate.of(2026, 10, 12), moved.startDate)
        assertEquals(LocalDate.of(2026, 10, 14), moved.endDate)
    }
}
