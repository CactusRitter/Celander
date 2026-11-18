package com.celander.app.account.microsoft

import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.model.AccessRole
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GraphCalendarMapperTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()
    private val start = LocalDate.of(2026, 11, 20) // a Friday

    private fun rrule(pattern: String, range: String = """{"type":"noEnd"}""") =
        GraphCalendarMapper.recurrenceToRrule(json("""{"pattern":$pattern,"range":$range}"""))

    @Test
    fun aCalendarTakesItsRoleFromWhatTheUserMayDo() {
        val own = GraphCalendarMapper.calendar(json("""{"id":"c1","name":"Calendar","hexColor":"#0078d4","canEdit":true,"isDefaultCalendar":true}"""))!!
        assertEquals(AccessRole.OWNER, own.accessRole)
        assertTrue(own.isPrimary)
        assertEquals("#0078d4", own.color)
        val shared = GraphCalendarMapper.calendar(json("""{"id":"c2","name":"Team","hexColor":"","canEdit":false,"isDefaultCalendar":false}"""))!!
        assertEquals(AccessRole.READER, shared.accessRole)
        assertNull(shared.color)
        assertEquals(AccessRole.WRITER, GraphCalendarMapper.calendar(json("""{"id":"c3","name":"Mine","canEdit":true}"""))!!.accessRole)
    }

    @Test
    fun aTimedEventIsReadAsInstantsInItsOwnZone() {
        val e = GraphCalendarMapper.event(
            json(
                """{"id":"e1","@odata.etag":"W/\"x\"","iCalUId":"u1","subject":"Dentist","body":{"contentType":"text","content":" Bring card \n"},
                |"location":{"displayName":"Main St"},"isAllDay":false,"isReminderOn":true,"reminderMinutesBeforeStart":15,
                |"lastModifiedDateTime":"2026-11-01T10:00:00Z","originalStartTimeZone":"W. Europe Standard Time",
                |"start":{"dateTime":"2026-11-20T14:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-11-20T14:45:00.0000000","timeZone":"UTC"}}""".trimMargin(),
            ),
        )!!
        val i = e.ical!!
        assertEquals("u1", i.uid)
        assertEquals("Dentist", i.summary)
        assertEquals("Bring card", i.description)
        assertEquals("Main St", i.location)
        assertEquals(ms("2026-11-20T14:00:00Z"), i.start)
        assertEquals(ms("2026-11-20T14:45:00Z"), i.end)
        assertEquals("Europe/Berlin", i.timeZone)
        assertEquals(listOf(15), e.reminders)
        assertEquals("W/\"x\"", e.etag)
        assertEquals(ms("2026-11-01T10:00:00Z"), e.updated)
    }

    @Test
    fun aWholeDayEventKeepsItsDatesAndAReminderOffMeansNone() {
        val e = GraphCalendarMapper.event(
            json("""{"id":"e2","iCalUId":"u2","subject":"Holiday","isAllDay":true,"isReminderOn":false,"start":{"dateTime":"2026-12-24T00:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-12-25T00:00:00.0000000","timeZone":"UTC"}}"""),
        )!!
        val i = e.ical!!
        assertTrue(i.allDay)
        assertEquals(ms("2026-12-24T00:00:00Z"), i.start)
        assertEquals(ms("2026-12-25T00:00:00Z"), i.end)
        assertEquals("UTC", i.timeZone)
        assertEquals(emptyList<Int>(), e.reminders)
    }

    @Test
    fun anExceptionNamesTheStartItReplacedAndTakesTheSeriesUid() {
        val e = GraphCalendarMapper.event(
            json(
                """{"id":"i1","type":"exception","subject":"Gym late","originalStart":"2026-11-27T10:00:00Z","originalStartTimeZone":"UTC",
                |"start":{"dateTime":"2026-11-27T12:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-11-27T13:00:00.0000000","timeZone":"UTC"}}""".trimMargin(),
            ),
            seriesUid = "series-uid",
        )!!
        val i = e.ical!!
        assertEquals("series-uid", i.uid)
        assertEquals(ms("2026-11-27T10:00:00Z"), i.recurrenceId)
        assertEquals(ms("2026-11-27T12:00:00Z"), i.start)
    }

    @Test
    fun graphPatternsBecomeRrules() {
        assertEquals("FREQ=DAILY;INTERVAL=2", rrule("""{"type":"daily","interval":2}"""))
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO,WE",
            rrule("""{"type":"weekly","interval":1,"daysOfWeek":["monday","wednesday"],"firstDayOfWeek":"monday"}"""),
        )
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", rrule("""{"type":"absoluteMonthly","interval":1,"dayOfMonth":15}"""))
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", rrule("""{"type":"relativeMonthly","interval":1,"daysOfWeek":["tuesday"],"index":"second"}"""))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", rrule("""{"type":"relativeMonthly","interval":1,"daysOfWeek":["friday"],"index":"last"}"""))
        assertEquals("FREQ=YEARLY;BYMONTHDAY=8;BYMONTH=3", rrule("""{"type":"absoluteYearly","interval":1,"month":3,"dayOfMonth":8}"""))
    }

    @Test
    fun theEndOfARepeatIsADateOrACount() {
        assertEquals("FREQ=DAILY;COUNT=5", rrule("""{"type":"daily","interval":1}""", """{"type":"numbered","numberOfOccurrences":5}"""))
        assertEquals("FREQ=DAILY;UNTIL=20261231", rrule("""{"type":"daily","interval":1}""", """{"type":"endDate","endDate":"2026-12-31"}"""))
    }

    private fun back(rule: String) = GraphCalendarMapper.rruleToRecurrence(rule, start, "Europe/Berlin")
    private fun JsonObject.pattern() = this["pattern"] as JsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun rrulesBecomeGraphPatterns() {
        val daily = back("FREQ=DAILY;INTERVAL=3")
        assertEquals("daily", daily.pattern().text("type"))
        assertEquals("3", daily.pattern().text("interval"))
        // A weekly rule without days repeats on the weekday of the start.
        assertEquals("""["friday"]""", back("FREQ=WEEKLY").pattern()["daysOfWeek"].toString())
        val monthly = back("FREQ=MONTHLY")
        assertEquals("absoluteMonthly", monthly.pattern().text("type"))
        assertEquals("20", monthly.pattern().text("dayOfMonth"))
        val relative = back("FREQ=MONTHLY;BYDAY=2TU").pattern()
        assertEquals("relativeMonthly", relative.text("type"))
        assertEquals("second", relative.text("index"))
        val yearly = back("FREQ=YEARLY").pattern()
        assertEquals("absoluteYearly", yearly.text("type"))
        assertEquals("11", yearly.text("month"))
        assertEquals("20", yearly.text("dayOfMonth"))
        val range = back("FREQ=DAILY;COUNT=4")["range"] as JsonObject
        assertEquals("numbered", range.text("type"))
        assertEquals("Europe/Berlin", range.text("recurrenceTimeZone"))
        assertEquals("2026-11-20", range.text("startDate"))
        assertEquals("endDate", ((back("FREQ=DAILY;UNTIL=20261231")["range"]) as JsonObject).text("type"))
    }

    @Test
    fun whatMicrosoftCannotRepeatIsRefused() {
        listOf("FREQ=HOURLY", "FREQ=MONTHLY;BYMONTHDAY=1,15", "FREQ=WEEKLY;BYHOUR=9").forEach { rule ->
            try {
                back(rule)
                fail("should refuse $rule")
            } catch (_: UnsupportedRecurrenceException) {
            }
        }
    }

    @Test
    fun everyPatternSurvivesTheRoundTrip() {
        listOf(
            "FREQ=DAILY;INTERVAL=2;COUNT=10", "FREQ=WEEKLY;BYDAY=MO,TH", "FREQ=MONTHLY;BYMONTHDAY=15", "FREQ=MONTHLY;BYDAY=-1FR",
            "FREQ=YEARLY;BYMONTHDAY=8;BYMONTH=3", "FREQ=DAILY;UNTIL=20261231",
        ).forEach { rule ->
            assertEquals(rule, GraphCalendarMapper.recurrenceToRrule(back(rule)))
        }
    }

    @Test
    fun anEventIsWrittenInItsZoneWithItsReminderAndRepeat() {
        val ical = ICalEvent(
            uid = "u", summary = "Gym", description = "d", location = "Hall", start = ms("2026-11-20T09:00:00Z"), end = ms("2026-11-20T10:00:00Z"),
            allDay = false, timeZone = "Europe/Berlin", rrule = "FREQ=WEEKLY",
        )
        val body = GraphCalendarMapper.toJson(ical, listOf(30, 10))
        assertEquals("Gym", body.text("subject"))
        val startObj = body["start"] as JsonObject
        assertEquals("2026-11-20T10:00:00", startObj.text("dateTime"))
        assertEquals("Europe/Berlin", startObj.text("timeZone"))
        assertEquals("true", body.text("isReminderOn"))
        assertEquals("10", body.text("reminderMinutesBeforeStart"))
        assertEquals("weekly", (body["recurrence"] as JsonObject).pattern().text("type"))
        // An occurrence travels without repeat rule.
        val occurrence = GraphCalendarMapper.toJson(ical.copy(rrule = null, recurrenceId = ms("2026-11-27T09:00:00Z")), emptyList())
        assertFalse(occurrence.containsKey("recurrence"))
        assertEquals("false", occurrence.text("isReminderOn"))
    }

    @Test
    fun aWholeDayEventIsWrittenAsUtcMidnights() {
        val body = GraphCalendarMapper.toJson(
            ICalEvent(uid = "u", summary = "Holiday", start = ms("2026-12-24T00:00:00Z"), end = ms("2026-12-25T00:00:00Z"), allDay = true, timeZone = "UTC"),
            emptyList(),
        )
        assertEquals("true", body.text("isAllDay"))
        assertEquals("2026-12-24T00:00:00", (body["start"] as JsonObject).text("dateTime"))
        assertEquals("2026-12-25T00:00:00", (body["end"] as JsonObject).text("dateTime"))
        assertEquals("UTC", (body["start"] as JsonObject).text("timeZone"))
    }
}
