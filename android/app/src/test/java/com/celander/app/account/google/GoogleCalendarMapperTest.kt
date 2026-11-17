package com.celander.app.account.google

import com.celander.app.core.ical.IcsMapper
import com.celander.app.core.model.AccessRole
import com.celander.app.core.model.EventTiming
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarMapperTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun aCalendarTakesTheUsersNameAndItsRole() {
        val c = GoogleCalendarMapper.calendar(json("""{"id":"a@b.c","summary":"Work","summaryOverride":"Mine","backgroundColor":"#16a765","accessRole":"writer","primary":true,"timeZone":"Europe/Berlin"}"""))!!
        assertEquals("Mine", c.name)
        assertEquals(AccessRole.WRITER, c.accessRole)
        assertTrue(c.isPrimary)
        assertEquals("#16a765", c.color)
        assertEquals(AccessRole.READER, GoogleCalendarMapper.role("reader"))
        assertEquals(AccessRole.FREE_BUSY, GoogleCalendarMapper.role("freeBusyReader"))
    }

    @Test
    fun aTimedEventKeepsItsInstantsAndZone() {
        val e = GoogleCalendarMapper.event(
            json("""{"id":"e1","etag":"\"3\"","updated":"2026-11-01T10:00:00.000Z","summary":"Dentist","location":"Main St","start":{"dateTime":"2026-11-20T15:00:00+01:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-11-20T15:45:00+01:00","timeZone":"Europe/Berlin"},"iCalUID":"u1@google.com","reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":10},{"method":"email","minutes":60},{"method":"popup","minutes":1440}]}}"""),
            calendarZone = "UTC",
        )!!
        val i = e.ical!!
        assertEquals("u1@google.com", i.uid)
        assertEquals(Instant.parse("2026-11-20T14:00:00Z").toEpochMilli(), i.start)
        assertEquals(Instant.parse("2026-11-20T14:45:00Z").toEpochMilli(), i.end)
        assertEquals("Europe/Berlin", i.timeZone)
        assertFalse(i.allDay)
        assertEquals(listOf(1440, 10), e.reminders)
        assertEquals("\"3\"", e.etag)
    }

    @Test
    fun anAllDayEventEndsTheDayBeforeGoogleSaysItDoes() {
        val e = GoogleCalendarMapper.event(json("""{"id":"e2","summary":"Trip","start":{"date":"2026-12-01"},"end":{"date":"2026-12-04"},"reminders":{"useDefault":true}}"""), null)!!
        assertNull(e.reminders)
        val event = IcsMapper.fromIcal(listOf(e.ical!!), calendarId = 1).single().first
        assertEquals(EventTiming.AllDay(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3)), event.timing)
    }

    @Test
    fun aDeletedEventHasNothingToShow() {
        val e = GoogleCalendarMapper.event(json("""{"id":"gone","status":"cancelled"}"""), null)!!
        assertNull(e.ical)
        assertEquals("gone", e.remoteId)
    }

    @Test
    fun aSeriesWithAChangedAndACancelledOccurrenceBecomesOneEventWithTwoExceptions() {
        val master = GoogleCalendarMapper.event(
            json("""{"id":"s1","iCalUID":"s@google.com","summary":"Gym","start":{"dateTime":"2026-11-02T18:00:00+01:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-11-02T19:00:00+01:00","timeZone":"Europe/Berlin"},"recurrence":["RRULE:FREQ=WEEKLY;BYDAY=MO","EXDATE;TZID=Europe/Berlin:20261109T180000"]}"""), null,
        )!!
        val moved = GoogleCalendarMapper.event(
            json("""{"id":"s1_20261116T170000Z","iCalUID":"s@google.com","recurringEventId":"s1","summary":"Gym (late)","start":{"dateTime":"2026-11-16T20:00:00+01:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-11-16T21:00:00+01:00","timeZone":"Europe/Berlin"},"originalStartTime":{"dateTime":"2026-11-16T18:00:00+01:00","timeZone":"Europe/Berlin"}}"""), null,
        )!!
        val skipped = GoogleCalendarMapper.event(
            json("""{"id":"s1_20261123T170000Z","iCalUID":"s@google.com","recurringEventId":"s1","status":"cancelled","originalStartTime":{"dateTime":"2026-11-23T18:00:00+01:00","timeZone":"Europe/Berlin"}}"""), null,
        )!!
        assertEquals("FREQ=WEEKLY;BYDAY=MO", master.ical!!.rrule)
        assertEquals(1, master.ical!!.exdates.size)

        val result = IcsMapper.fromIcal(listOf(master.ical!!, moved.ical!!, skipped.ical!!), calendarId = 1)
        assertEquals(1, result.size)
        val (event, exceptions) = result.single()
        assertEquals("Gym", event.title)
        assertEquals(3, exceptions.size)
        val cancelled = exceptions.filter { it.cancelled }
        assertEquals(2, cancelled.size)
        val changed = exceptions.single { !it.cancelled }
        assertEquals("Gym (late)", changed.title)
        assertNotNull(changed.timing)
        assertEquals(Instant.parse("2026-11-16T17:00:00Z").toEpochMilli(), changed.originalStart)
    }
}
