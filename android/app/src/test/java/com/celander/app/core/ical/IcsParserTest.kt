package com.celander.app.core.ical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class IcsParserTest {
    private val sample = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//EN
        X-WR-CALNAME:Team
        BEGIN:VTIMEZONE
        TZID:Europe/Berlin
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:weekly-1@test
        DTSTART;TZID=Europe/Berlin:20261005T090000
        DTEND;TZID=Europe/Berlin:20261005T093000
        SUMMARY:Stand-up\, täglich
        DESCRIPTION:Zeile 1\nZeile 2 mit einer sehr langen Beschreibung\, die über
          mehrere physische Zeilen gefaltet ist
        LOCATION:Raum 3
        RRULE:FREQ=WEEKLY;BYDAY=MO,WE
        EXDATE;TZID=Europe/Berlin:20261012T090000
        ATTENDEE;CN="Doe, Jane":mailto:jane@example.com
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT15M
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:weekly-1@test
        RECURRENCE-ID;TZID=Europe/Berlin:20261014T090000
        DTSTART;TZID=Europe/Berlin:20261014T100000
        DTEND;TZID=Europe/Berlin:20261014T103000
        SUMMARY:Stand-up (verschoben)
        END:VEVENT
        BEGIN:VEVENT
        UID:holiday@test
        DTSTART;VALUE=DATE:20261003
        SUMMARY:Tag der Deutschen Einheit
        END:VEVENT
        BEGIN:VEVENT
        UID:utc@test
        DTSTART:20261023T120000Z
        DURATION:PT1H30M
        SUMMARY:UTC
        STATUS:CANCELLED
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun parsesEventsAndCalendarName() {
        val cal = IcsParser.parseCalendar(sample)
        assertEquals("Team", cal.name)
        assertEquals(4, cal.events.size)
    }

    @Test
    fun parsesTimedRecurringEvent() {
        val e = IcsParser.parse(sample).first()
        assertEquals("weekly-1@test", e.uid)
        assertEquals("Stand-up, täglich", e.summary)
        assertEquals("Zeile 1\nZeile 2 mit einer sehr langen Beschreibung, die über mehrere physische Zeilen gefaltet ist", e.description)
        assertEquals("Raum 3", e.location)
        assertEquals(ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, berlin).toInstant().toEpochMilli(), e.start)
        assertEquals(30 * 60_000L, e.end - e.start)
        assertEquals("Europe/Berlin", e.timeZone)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", e.rrule)
        assertEquals(listOf(ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, berlin).toInstant().toEpochMilli()), e.exdates)
        assertEquals(15, e.reminderMinutes)
        assertTrue(e.extra!!.contains("ATTENDEE;CN=\"Doe, Jane\":mailto:jane@example.com"))
    }

    @Test
    fun parsesOverride() {
        val o = IcsParser.parse(sample)[1]
        assertEquals("weekly-1@test", o.uid)
        assertEquals(ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, berlin).toInstant().toEpochMilli(), o.recurrenceId)
        assertNull(o.rrule)
    }

    @Test
    fun allDayDefaultsToOneDay() {
        val e = IcsParser.parse(sample)[2]
        assertTrue(e.allDay)
        assertEquals(LocalDate.of(2026, 10, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), e.start)
        assertEquals(86_400_000L, e.end - e.start)
    }

    @Test
    fun durationAndStatus() {
        val e = IcsParser.parse(sample)[3]
        assertEquals(90 * 60_000L, e.end - e.start)
        assertTrue(e.cancelled)
    }

    @Test
    fun windowsTimeZoneNames() {
        assertEquals(ZoneId.of("Europe/Berlin"), TimeZones.resolve("W. Europe Standard Time"))
        assertEquals(ZoneId.of("Europe/Berlin"), TimeZones.resolve("/mozilla.org/20050126_1/Europe/Berlin"))
    }

    @Test
    fun roundTripThroughWriter() {
        val original = IcsParser.parse(sample)
        val written = IcsWriter.calendar(original, "Team")
        assertTrue(written.contains("BEGIN:VTIMEZONE"))
        assertTrue(written.lines().all { it.toByteArray().size <= 75 })
        val reparsed = IcsParser.parse(written)
        assertEquals(original.size, reparsed.size)
        original.zip(reparsed).forEach { (a, b) ->
            assertEquals(a.uid, b.uid)
            assertEquals(a.summary, b.summary)
            assertEquals(a.description, b.description)
            assertEquals(a.start, b.start)
            assertEquals(a.end, b.end)
            assertEquals(a.allDay, b.allDay)
            assertEquals(a.rrule, b.rrule)
            assertEquals(a.exdates, b.exdates)
            assertEquals(a.recurrenceId, b.recurrenceId)
            assertEquals(a.reminderMinutes, b.reminderMinutes)
            assertEquals(a.cancelled, b.cancelled)
        }
        assertNotNull(reparsed.first().extra)
    }

    @Test
    fun vtimezoneHasDaylightRules() {
        val lines = IcsWriter.vtimezone(berlin)
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"))
        assertTrue(lines.contains("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU"))
        assertTrue(lines.contains("TZOFFSETTO:+0200"))
    }
}
