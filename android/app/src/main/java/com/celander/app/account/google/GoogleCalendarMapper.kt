package com.celander.app.account.google

import com.celander.app.account.RemoteCalendar
import com.celander.app.account.RemoteEvent
import com.celander.app.core.ical.ContentLine
import com.celander.app.core.ical.ICalDates
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.model.AccessRole
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Google Calendar's JSON (API v3) as the app's provider types. */
object GoogleCalendarMapper {
    private const val DAY_MS = 86_400_000L

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun calendar(json: JsonObject): RemoteCalendar? {
        val id = json.str("id") ?: return null
        return RemoteCalendar(
            remoteId = id,
            // The name the user gave it in Google Calendar wins over the calendar's own title.
            name = json.str("summaryOverride")?.takeIf { it.isNotBlank() } ?: json.str("summary").orEmpty().ifBlank { id },
            color = json.str("backgroundColor"),
            accessRole = role(json.str("accessRole")),
            isPrimary = json.str("primary") == "true",
            timeZone = json.str("timeZone"),
        )
    }

    fun role(value: String?): AccessRole = when (value) {
        "owner" -> AccessRole.OWNER
        "writer" -> AccessRole.WRITER
        "freeBusyReader" -> AccessRole.FREE_BUSY
        else -> AccessRole.READER
    }

    /** A moment of an event: milliseconds, whether it is a plain date, and the zone it is written in. */
    private data class When(val millis: Long, val allDay: Boolean, val zone: String)

    private fun moment(json: JsonObject?, calendarZone: String?): When? {
        json ?: return null
        json.str("date")?.let { date ->
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            return When(day.toEpochDay() * DAY_MS, allDay = true, zone = "UTC")
        }
        val text = json.str("dateTime") ?: return null
        val at = runCatching { OffsetDateTime.parse(text) }.getOrNull() ?: return null
        // The zone a recurring event repeats in; without one, the calendar's (else the offset it was sent with).
        val zone = json.str("timeZone") ?: calendarZone ?: at.offset.id.let { if (it == "Z") "UTC" else it }
        return When(at.toInstant().toEpochMilli(), allDay = false, zone = zone)
    }

    private fun instant(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    /**
     * One event of an events list. A deleted event comes with little more than its id and status: its
     * [RemoteEvent.ical] is null. A cancelled occurrence of a series is kept (marked cancelled) so the series
     * learns about it. [calendarZone] is the zone of the calendar, for events that do not name one.
     */
    fun event(json: JsonObject, calendarZone: String?): RemoteEvent? {
        val id = json.str("id") ?: return null
        val etag = json.str("etag")
        val updated = json.str("updated")?.let(::instant) ?: 0L
        val cancelled = json.str("status") == "cancelled"
        val start = moment(json.obj("start"), calendarZone)
        val original = moment(json.obj("originalStartTime"), calendarZone)
        if (start == null && !(cancelled && original != null)) {
            // Nothing to show: a deleted event, or one the server sent without a time.
            return RemoteEvent(id, etag, updated, ical = null)
        }
        // A cancelled occurrence has no start of its own: it takes the one it replaced.
        val from = start ?: original!!
        val to = moment(json.obj("end"), calendarZone)
        val end = when {
            to == null || to.millis < from.millis -> from.millis + if (from.allDay) DAY_MS else 0L
            else -> to.millis
        }
        val lines = (json["recurrence"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val rrule = lines.firstOrNull { it.startsWith("RRULE:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val exdates = lines.filter { it.startsWith("EXDATE", ignoreCase = true) }
            .mapNotNull(ContentLine::parse)
            .flatMap(ICalDates::parseList)
            .map { it.millis }
        val ical = ICalEvent(
            // A series and its changed occurrences share the iCalendar UID.
            uid = json.str("iCalUID")?.takeIf { it.isNotBlank() } ?: json.str("recurringEventId") ?: id,
            summary = json.str("summary").orEmpty(),
            description = json.str("description").orEmpty(),
            location = json.str("location").orEmpty(),
            start = from.millis,
            end = end,
            allDay = from.allDay,
            timeZone = from.zone,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = original?.millis,
            recurrenceAllDay = original?.allDay,
            recurrenceTimeZone = original?.zone,
            cancelled = cancelled,
        )
        return RemoteEvent(id, etag, updated, ical, reminders = reminders(json.obj("reminders")))
    }

    /** The popup reminders an event carries; null when it uses the calendar's own defaults (which the app does not know). */
    private fun reminders(json: JsonObject?): List<Int>? {
        json ?: return null
        if (json.str("useDefault") == "true") return null
        val overrides = json["overrides"] as? JsonArray ?: return emptyList()
        return overrides.mapNotNull { it as? JsonObject }
            .filter { it.str("method") != "email" }
            .mapNotNull { (it["minutes"] as? JsonPrimitive)?.intOrNull }
            .distinct()
            .sortedDescending()
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

}
