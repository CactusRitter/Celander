package com.celander.app.core.ical

import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class IcsMapperTest {
    @Test
    fun seriesWithExceptionsSurvivesWriteAndParse() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY;BYDAY=MO").copy(
            location = "Room 3",
            description = "Line 1\nLine 2, with comma",
            reminders = listOf(10),
        )
        val cancelledKey = LocalDateTime.of(2026, 10, 12, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()
        val changedKey = LocalDateTime.of(2026, 10, 19, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()
        val changedTiming = EventTiming.Timed(
            LocalDateTime.of(2026, 10, 20, 14, 0).atZone(BERLIN).toInstant(),
            LocalDateTime.of(2026, 10, 20, 15, 0).atZone(BERLIN).toInstant(),
            BERLIN,
        )
        val exceptions = listOf(
            EventException(eventId = 1, originalStart = cancelledKey, cancelled = true),
            EventException(eventId = 1, originalStart = changedKey, title = "Moved", timing = changedTiming),
        )

        val text = IcsWriter.calendar(IcsMapper.toIcal(event, exceptions), name = "Test", nowMillis = 0)
        val (imported, importedExceptions) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 7).single()

        assertEquals(event.uid, imported.uid)
        assertEquals(7L, imported.calendarId)
        assertEquals(event.title, imported.title)
        assertEquals(event.location, imported.location)
        assertEquals(event.description, imported.description)
        assertEquals(event.timing, imported.timing)
        assertEquals(event.rrule, imported.rrule)
        assertEquals(listOf(10), imported.reminders)
        assertEquals(setOf(cancelledKey, changedKey), importedExceptions.map { it.originalStart }.toSet())
        val changed = importedExceptions.single { !it.cancelled }
        assertEquals("Moved", changed.title)
        assertEquals(changedTiming, changed.timing)
    }

    @Test
    fun allDayRangeKeepsInclusiveEnd() {
        val event = allDayEvent(1, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1))
        val text = IcsWriter.calendar(IcsMapper.toIcal(event, emptyList()), nowMillis = 0)
        val (imported, _) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(event.timing, imported.timing)
    }

    @Test
    fun overrideWithoutMasterBecomesSingleEvent() {
        val orphan = ICalEvent(uid = "x", summary = "Orphan", start = 0, end = 3_600_000, allDay = false, timeZone = "UTC", recurrenceId = 0)
        val (event, exceptions) = IcsMapper.fromIcal(listOf(orphan), calendarId = 1).single()
        assertEquals("Orphan", event.title)
        assertEquals(null, event.rrule)
        assertEquals(emptyList<EventException>(), exceptions)
    }
}
