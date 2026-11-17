package com.celander.app.core.ical

import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reminders after the start, RECURRENCE-IDs of changed occurrences, nominal durations, cancelled series, line breaks. */
class IcsEdgeCasesTest {
    @Test
    fun allDayReminderOnTheDayIsWrittenAfterTheStart() {
        // -540 means "on the day at 9:00".
        val event = allDayEvent(1, LocalDate.of(2026, 10, 30)).copy(reminders = listOf(-540))
        val text = IcsWriter.calendar(IcsMapper.toIcal(event, emptyList()), nowMillis = 0)
        assertTrue(text, text.contains("TRIGGER:PT540M\r\n"))
        val (imported, _) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(listOf(-540), imported.reminders)
    }

    @Test
    fun timedReminderAfterTheStartFiresAtTheStart() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:late@test
            DTSTART;TZID=Europe/Berlin:20261026T090000
            DTEND;TZID=Europe/Berlin:20261026T100000
            SUMMARY:Late alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val parsed = IcsParser.parse(text).single()
        assertEquals(-10, parsed.reminderMinutes)
        assertEquals(listOf(0), IcsMapper.fromIcal(listOf(parsed), calendarId = 1).single().first.reminders)
    }

    @Test
    fun recurrenceIdOfAnOccurrenceMadeAllDayFollowsTheSeries() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY;BYDAY=MO")
        val key = LocalDateTime.of(2026, 10, 19, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()
        val allDay = EventTiming.AllDay(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
        val exceptions = listOf(EventException(eventId = 1, originalStart = key, timing = allDay))

        val text = IcsWriter.calendar(IcsMapper.toIcal(event, exceptions), nowMillis = 0)

        assertTrue(text, text.contains("RECURRENCE-ID;TZID=Europe/Berlin:20261019T090000\r\n"))
        assertTrue(text, text.contains("DTSTART;VALUE=DATE:20261020\r\n"))
        val (_, imported) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(key, imported.single().originalStart)
        assertEquals(allDay, imported.single().timing)
    }

    @Test
    fun zoneOfARecurrenceIdIsDefinedEvenIfOnlyItUsesIt() {
        val override = ICalEvent(
            uid = "x",
            start = 0,
            end = 86_400_000,
            allDay = true,
            timeZone = "UTC",
            recurrenceId = 0,
            recurrenceAllDay = false,
            recurrenceTimeZone = "America/New_York",
        )
        val text = IcsWriter.calendar(listOf(override), nowMillis = 0)
        assertTrue(text, text.contains("TZID:America/New_York\r\n"))
        assertTrue(text, text.contains("RECURRENCE-ID;TZID=America/New_York:19691231T190000\r\n"))
    }

    @Test
    fun dateRecurrenceIdOfATimedSeriesFindsThatDaysOccurrence() {
        // As written by earlier versions for an occurrence made all-day.
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:weekly@test
            DTSTART;TZID=Europe/Berlin:20261005T090000
            DTEND;TZID=Europe/Berlin:20261005T093000
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Jour fixe
            END:VEVENT
            BEGIN:VEVENT
            UID:weekly@test
            RECURRENCE-ID;VALUE=DATE:20261026
            DTSTART;VALUE=DATE:20261027
            SUMMARY:Jour fixe (all day)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val (_, exceptions) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        // 26 October is after the change to winter time; the occurrence is still at 9:00.
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0).atZone(BERLIN).toInstant().toEpochMilli(), exceptions.single().originalStart)
    }

    @Test
    fun dateRecurrenceIdOfEarlierVersionsIsTheUtcDateOfTheOccurrence() {
        // Monday 19 October 00:30 in Berlin is 18 October 22:30 UTC; earlier versions wrote that UTC date.
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:night@test
            DTSTART;TZID=Europe/Berlin:20261005T003000
            DTEND;TZID=Europe/Berlin:20261005T010000
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Night shift
            END:VEVENT
            BEGIN:VEVENT
            UID:night@test
            RECURRENCE-ID;VALUE=DATE:20261018
            DTSTART;VALUE=DATE:20261019
            SUMMARY:Night shift (all day)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val (_, exceptions) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 30).atZone(BERLIN).toInstant().toEpochMilli(), exceptions.single().originalStart)
    }

    @Test
    fun dateRecurrenceIdOnTheLocalDayIsThatDaysOccurrence() {
        // Written by another app as the local date: the UTC reading (Tuesday 00:30) is no occurrence.
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:night@test
            DTSTART;TZID=Europe/Berlin:20261005T003000
            DTEND;TZID=Europe/Berlin:20261005T010000
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Night shift
            END:VEVENT
            BEGIN:VEVENT
            UID:night@test
            RECURRENCE-ID;VALUE=DATE:20261019
            DTSTART;VALUE=DATE:20261019
            SUMMARY:Night shift (all day)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val (_, exceptions) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 30).atZone(BERLIN).toInstant().toEpochMilli(), exceptions.single().originalStart)
    }

    @Test
    fun timeRecurrenceIdOfEarlierVersionsIsUtcMidnightOfTheDay() {
        // Earlier versions wrote UTC midnight of 10 March in the zone of the occurrence: 9 March 20:00 in New York.
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:daily@test
            DTSTART;VALUE=DATE:20260301
            RRULE:FREQ=DAILY
            SUMMARY:Daily
            END:VEVENT
            BEGIN:VEVENT
            UID:daily@test
            RECURRENCE-ID;TZID=America/New_York:20260309T200000
            DTSTART;TZID=America/New_York:20260310T140000
            DTEND;TZID=America/New_York:20260310T150000
            SUMMARY:Daily (at two)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val (_, exceptions) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(LocalDate.of(2026, 3, 10).toEpochDay(), exceptions.single().originalStart)
    }

    @Test
    fun timeRecurrenceIdAtLocalMidnightIsThatDay() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:daily@test
            DTSTART;VALUE=DATE:20260301
            RRULE:FREQ=DAILY
            SUMMARY:Daily
            END:VEVENT
            BEGIN:VEVENT
            UID:daily@test
            RECURRENCE-ID;TZID=America/New_York:20260310T000000
            DTSTART;TZID=America/New_York:20260310T140000
            DTEND;TZID=America/New_York:20260310T150000
            SUMMARY:Daily (at two)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val (_, exceptions) = IcsMapper.fromIcal(IcsParser.parse(text), calendarId = 1).single()
        assertEquals(LocalDate.of(2026, 3, 10).toEpochDay(), exceptions.single().originalStart)
    }

    @Test
    fun occurrencesThatChangedTypeComeBackFromEarlierExports() {
        // Earlier versions wrote RECURRENCE-ID like the changed occurrence, not like the series.
        fun roundTrip(event: Event, exception: EventException): Long {
            val written = IcsMapper.toIcal(event, listOf(exception)).map { it.copy(recurrenceAllDay = null, recurrenceTimeZone = null) }
            return IcsMapper.fromIcal(IcsParser.parse(IcsWriter.calendar(written, nowMillis = 0)), calendarId = 1).single().second.single().originalStart
        }
        val night = timedEvent(1, LocalDateTime.of(2026, 10, 5, 0, 30), 30, rrule = "FREQ=WEEKLY;BYDAY=MO")
        val monday = LocalDateTime.of(2026, 10, 19, 0, 30).atZone(BERLIN).toInstant().toEpochMilli()
        val madeAllDay = EventException(eventId = 1, originalStart = monday, timing = EventTiming.AllDay(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19)))
        assertEquals(monday, roundTrip(night, madeAllDay))

        val newYork = ZoneId.of("America/New_York")
        val tuesdays = allDayEvent(2, LocalDate.of(2026, 3, 3), rrule = "FREQ=WEEKLY")
        val tenth = LocalDate.of(2026, 3, 10)
        val madeTimed = EventException(
            eventId = 2,
            originalStart = tenth.toEpochDay(),
            timing = EventTiming.Timed(tenth.atTime(14, 0).atZone(newYork).toInstant(), tenth.atTime(15, 0).atZone(newYork).toInstant(), newYork),
        )
        assertEquals(tenth.toEpochDay(), roundTrip(tuesdays, madeTimed))
    }

    @Test
    fun dayDurationsFollowTheWallClockAcrossDaylightSaving() {
        fun end(duration: String): ZonedDateTime {
            val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:d@test\r\nDTSTART;TZID=Europe/Berlin:20261024T100000\r\n" +
                "DURATION:$duration\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            return Instant.ofEpochMilli(IcsParser.parse(text).single().end).atZone(BERLIN)
        }
        // Summer time ends in the night to 25 October 2026, so that day has 25 hours.
        assertEquals(ZonedDateTime.of(2026, 10, 25, 10, 0, 0, 0, BERLIN), end("P1D"))
        assertEquals(ZonedDateTime.of(2026, 10, 25, 12, 30, 0, 0, BERLIN), end("P1DT2H30M"))
        assertEquals(ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, BERLIN), end("P1W"))
        assertEquals(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, BERLIN), end("PT24H"))
    }

    @Test
    fun cancelledSeriesIsLeftOutWithItsOverrides() {
        val cancelled = ICalEvent(uid = "gone", summary = "Called off", start = 0, end = 3_600_000, allDay = false, timeZone = "UTC", rrule = "FREQ=DAILY", cancelled = true)
        val override = ICalEvent(uid = "gone", summary = "Moved", start = 90_000_000, end = 93_600_000, allDay = false, timeZone = "UTC", recurrenceId = 86_400_000)
        val kept = ICalEvent(uid = "kept", summary = "Still on", start = 0, end = 3_600_000, allDay = false, timeZone = "UTC")
        val imported = IcsMapper.fromIcal(listOf(cancelled, override, kept), calendarId = 1)
        assertEquals(listOf("Still on"), imported.map { it.first.title })
    }

    @Test
    fun loneCarriageReturnStaysInsideTheText() {
        assertEquals("A\\nB", ContentLine.escapeText("A\rB"))
        val event = ICalEvent(uid = "cr", summary = "Notes", description = "Line 1\rLine 2", start = 0, end = 3_600_000, allDay = false, timeZone = "UTC")
        val parsed = IcsParser.parse(IcsWriter.calendar(listOf(event), nowMillis = 0)).single()
        assertEquals("Line 1\nLine 2", parsed.description)
    }
}
