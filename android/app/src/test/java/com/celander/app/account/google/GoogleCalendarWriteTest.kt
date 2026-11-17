package com.celander.app.account.google

import com.celander.app.core.ical.ICalEvent
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarWriteTest {
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun aTimedEventIsWrittenWithItsOffsetZoneAndPopupReminders() {
        val json = GoogleCalendarMapper.toJson(
            ICalEvent(uid = "u", summary = "Dentist", location = "Main St", description = "Bring card", start = ms("2026-11-20T14:00:00Z"), end = ms("2026-11-20T14:45:00Z"), allDay = false, timeZone = "Europe/Berlin"),
            reminders = listOf(10, 1440, 10),
        )
        assertEquals("Dentist", json.str("summary"))
        assertEquals("Main St", json.str("location"))
        val start = json["start"]!!.jsonObject
        assertEquals("2026-11-20T15:00:00+01:00", start.str("dateTime"))
        assertEquals("Europe/Berlin", start.str("timeZone"))
        assertEquals("2026-11-20T15:45:00+01:00", json["end"]!!.jsonObject.str("dateTime"))
        val reminders = json["reminders"]!!.jsonObject
        assertEquals("false", reminders.str("useDefault"))
        assertEquals(listOf("10", "1440"), reminders["overrides"]!!.jsonArray.map { it.jsonObject.str("minutes") })
        assertTrue(reminders["overrides"]!!.jsonArray.all { it.jsonObject.str("method") == "popup" })
        assertEquals(0, (json["recurrence"] as JsonArray).size)
    }

    @Test
    fun anAllDayEventUsesDatesAndAnExclusiveEnd() {
        val start = LocalDate.of(2026, 12, 1).toEpochDay() * 86_400_000L
        val json = GoogleCalendarMapper.toJson(ICalEvent(uid = "u", summary = "Trip", start = start, end = start + 3 * 86_400_000L, allDay = true, timeZone = "UTC"), emptyList())
        assertEquals("2026-12-01", json["start"]!!.jsonObject.str("date"))
        assertEquals("2026-12-04", json["end"]!!.jsonObject.str("date"))
        assertNull(json["start"]!!.jsonObject.str("dateTime"))
        assertEquals(0, json["reminders"]!!.jsonObject["overrides"]!!.jsonArray.size)
    }

    @Test
    fun aSeriesCarriesItsRuleAndTheExdatesOfCancelledOccurrences() {
        val json = GoogleCalendarMapper.toJson(
            ICalEvent(
                uid = "u", summary = "Gym", start = ms("2026-11-02T17:00:00Z"), end = ms("2026-11-02T18:00:00Z"), allDay = false, timeZone = "Europe/Berlin",
                rrule = "FREQ=WEEKLY;BYDAY=MO", exdates = listOf(ms("2026-11-09T17:00:00Z")),
            ),
            listOf(10),
        )
        val lines = json["recurrence"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("RRULE:FREQ=WEEKLY;BYDAY=MO", lines[0])
        assertEquals("EXDATE;TZID=Europe/Berlin:20261109T180000", lines[1])
    }

    @Test
    fun anOccurrenceHasNoRecurrenceLinesAndItsIdFollowsGoogleScheme() {
        val occurrence = ICalEvent(
            uid = "u", summary = "Gym late", start = ms("2026-11-16T19:00:00Z"), end = ms("2026-11-16T20:00:00Z"), allDay = false, timeZone = "UTC",
            recurrenceId = ms("2026-11-16T17:00:00Z"), recurrenceAllDay = false, recurrenceTimeZone = "UTC",
        )
        assertFalse(GoogleCalendarMapper.toJson(occurrence, emptyList()).containsKey("recurrence"))
        assertEquals("abc_20261116T170000Z", GoogleCalendarMapper.instanceId("abc", occurrence.recurrenceId!!, allDay = false))
        val day = LocalDate.of(2026, 11, 16).toEpochDay() * 86_400_000L
        assertEquals("abc_20261116", GoogleCalendarMapper.instanceId("abc", day, allDay = true))
    }

    @Test
    fun theAnswerOfAWriteGivesIdUidEtagAndTime() {
        val answer = kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"e1","iCalUID":"e1@google.com","etag":"\"7\"","updated":"2026-11-18T10:00:00.000Z"}""") as JsonObject
        val write = GoogleCalendarMapper.written(answer)!!
        assertEquals("e1", write.remoteId)
        assertEquals("e1@google.com", write.uid)
        assertEquals("\"7\"", write.etag)
        assertEquals(ms("2026-11-18T10:00:00Z"), write.updated)
    }
}
