package com.celander.app.account.microsoft

import com.celander.app.account.RemoteCalendar
import com.celander.app.account.RemoteEvent
import com.celander.app.account.RemoteWrite
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.ical.TimeZones
import com.celander.app.core.model.AccessRole
import com.celander.app.core.recurrence.Frequency
import com.celander.app.core.recurrence.RRule
import com.celander.app.core.recurrence.WeekdayNum
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A repeat rule that Microsoft's calendar cannot express (it has fewer patterns than iCalendar). */
class UnsupportedRecurrenceException(message: String) : IllegalArgumentException(message)

/**
 * Microsoft Graph's calendar JSON as the app's provider types, and back. Graph describes repeats with a structured pattern
 * (`patternedRecurrence`) instead of an RRULE, so [recurrenceToRrule] and [rruleToRecurrence] convert between the two.
 */
object GraphCalendarMapper {
    private const val DAY_MS = 86_400_000L
    private val DAY_NAMES = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private val INDEXES = listOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "last" to -1)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    fun calendar(json: JsonObject): RemoteCalendar? {
        val id = json.str("id") ?: return null
        val default = json.bool("isDefaultCalendar") == true
        val canEdit = json.bool("canEdit") != false
        return RemoteCalendar(
            remoteId = id,
            name = json.str("name").orEmpty().ifBlank { id },
            color = json.str("hexColor")?.takeIf { it.startsWith("#") && it.length == 7 },
            accessRole = when {
                !canEdit -> AccessRole.READER
                default -> AccessRole.OWNER
                else -> AccessRole.WRITER
            },
            isPrimary = default,
            selected = true,
            // Outlook has one reminder switch per calendar but no minutes the app could show.
            defaultReminders = emptyList(),
            timeZone = null,
        )
    }

    /** Graph's "2026-11-20T10:00:00.0000000" plus the zone it is in, as milliseconds. */
    private fun moment(json: JsonObject?): Long? {
        json ?: return null
        val text = json.str("dateTime") ?: return null
        val zone = TimeZones.resolve(json.str("timeZone") ?: "UTC")
        return runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    /** A moment that may come with an offset ("originalStart", "lastModifiedDateTime"). */
    private fun instant(text: String?): Long? = text?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /**
     * One event of an events list or of the instances of a series. [seriesUid] gives an occurrence the UID of its series
     * (Graph does not repeat it on every instance). A series master carries its repeat rule; an exception names the start it replaced.
     */
    fun event(json: JsonObject, seriesUid: String? = null): RemoteEvent? {
        val id = json.str("id") ?: return null
        val etag = json.str("@odata.etag")
        val updated = instant(json.str("lastModifiedDateTime")) ?: 0L
        val type = json.str("type")
        val allDay = json.bool("isAllDay") == true
        val zoneName = json.str("originalStartTimeZone") ?: json.obj("start")?.str("timeZone") ?: "UTC"
        val zone = TimeZones.resolve(zoneName)
        var start = moment(json.obj("start")) ?: return RemoteEvent(id, etag, updated, ical = null)
        var end = moment(json.obj("end")) ?: start
        if (allDay) {
            // A whole-day event is a date, not a moment: keep its dates as written, at UTC midnight.
            start = dayOf(json.obj("start")) ?: start
            end = dayOf(json.obj("end")) ?: (start + DAY_MS)
            if (end <= start) end = start + DAY_MS
        } else if (end < start) {
            end = start
        }
        val recurrence = json.obj("recurrence")
        val original = if (type == "exception" || type == "occurrence") instant(json.str("originalStart")) else null
        val ical = ICalEvent(
            uid = json.str("iCalUId")?.takeIf { it.isNotBlank() } ?: seriesUid ?: json.str("seriesMasterId") ?: id,
            summary = json.str("subject").orEmpty(),
            description = json.obj("body")?.str("content").orEmpty().trim(),
            location = json.obj("location")?.str("displayName").orEmpty(),
            start = start,
            end = end,
            allDay = allDay,
            timeZone = if (allDay) "UTC" else TimeZones.isUtc(zone).let { if (it) "UTC" else zone.id },
            rrule = recurrence?.let(::recurrenceToRrule),
            recurrenceId = original?.let { if (allDay) Math.floorDiv(it, DAY_MS) * DAY_MS else it },
            recurrenceAllDay = if (original != null) allDay else null,
            recurrenceTimeZone = if (original != null) (if (allDay) "UTC" else zone.id) else null,
            cancelled = json.bool("isCancelled") == true,
        )
        val reminders = when {
            json.bool("isReminderOn") == false -> emptyList()
            else -> json.int("reminderMinutesBeforeStart")?.let { listOf(it) }
        }
        return RemoteEvent(id, etag, updated, ical, reminders)
    }

    /** The date of an all-day moment, at UTC midnight. */
    private fun dayOf(json: JsonObject?): Long? =
        json?.str("dateTime")?.take(10)?.let { runCatching { LocalDate.parse(it).toEpochDay() * DAY_MS }.getOrNull() }

    // --- Repeat rules ---

    /** The RRULE of a Graph `patternedRecurrence`, or null when it has no usable pattern. */
    fun recurrenceToRrule(recurrence: JsonObject): String? {
        val pattern = recurrence.obj("pattern") ?: return null
        val range = recurrence.obj("range")
        val interval = (pattern.int("interval") ?: 1).coerceAtLeast(1)
        val days = (pattern["daysOfWeek"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.mapNotNull { DAY_NAMES[it.lowercase()] }
        val index = INDEXES.firstOrNull { it.first == pattern.str("index") }?.second
        val first = DAY_NAMES[pattern.str("firstDayOfWeek")?.lowercase()] ?: DayOfWeek.MONDAY
        val month = pattern.int("month")?.takeIf { it in 1..12 }
        val dayOfMonth = pattern.int("dayOfMonth")?.takeIf { it in 1..31 }
        val (until, count) = when (range?.str("type")) {
            "endDate" -> range.str("endDate")?.replace("-", "") to null
            "numbered" -> null to range.int("numberOfOccurrences")
            else -> null to null
        }
        val rule = when (pattern.str("type")) {
            "daily" -> RRule(Frequency.DAILY, interval, count, until)
            "weekly" -> RRule(Frequency.WEEKLY, interval, count, until, byDay = days.map { WeekdayNum(0, it) }, weekStart = first)
            "absoluteMonthly" -> RRule(Frequency.MONTHLY, interval, count, until, byMonthDay = listOfNotNull(dayOfMonth))
            "relativeMonthly" -> RRule(Frequency.MONTHLY, interval, count, until, byDay = relativeDays(days, index), bySetPos = setPos(days, index))
            "absoluteYearly" -> RRule(Frequency.YEARLY, interval, count, until, byMonth = listOfNotNull(month), byMonthDay = listOfNotNull(dayOfMonth))
            "relativeYearly" -> RRule(Frequency.YEARLY, interval, count, until, byDay = relativeDays(days, index), byMonth = listOfNotNull(month), bySetPos = setPos(days, index))
            else -> return null
        }
        return rule.format()
    }

    /** One weekday with an ordinal ("2TU", "-1FR") when the pattern names a single day; else plain weekdays with a set position. */
    private fun relativeDays(days: List<DayOfWeek>, index: Int?): List<WeekdayNum> =
        if (days.size == 1 && index != null) listOf(WeekdayNum(index, days.single())) else days.map { WeekdayNum(0, it) }

    private fun setPos(days: List<DayOfWeek>, index: Int?): List<Int> = if (days.size == 1 || index == null) emptyList() else listOf(index)

    /**
     * The `patternedRecurrence` of [rrule] for a series that starts on [start] in [zoneId]. Throws [UnsupportedRecurrenceException]
     * for what Microsoft cannot repeat (hourly, several days of the month, other rules).
     */
    fun rruleToRecurrence(rrule: String, start: LocalDate, zoneId: String, rangeStart: LocalDate = start): JsonObject {
        val rule = RRule.parse(rrule) ?: throw UnsupportedRecurrenceException("unreadable rule")
        if (rule.extras.isNotEmpty()) throw UnsupportedRecurrenceException("parts: " + rule.extras.joinToString { it.first })
        val pattern = buildJsonObject {
            put("interval", rule.interval)
            when (rule.freq) {
                Frequency.DAILY -> {
                    if (rule.byDay.isNotEmpty() || rule.byMonthDay.isNotEmpty()) throw UnsupportedRecurrenceException("daily with days")
                    put("type", "daily")
                }
                Frequency.WEEKLY -> {
                    put("type", "weekly")
                    putDays(rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) })
                    put("firstDayOfWeek", rule.weekStart.name.lowercase())
                }
                Frequency.MONTHLY -> monthly(rule, start)
                Frequency.YEARLY -> yearly(rule, start)
                else -> throw UnsupportedRecurrenceException(rule.freq.name)
            }
        }
        return buildJsonObject {
            put("pattern", pattern)
            putJsonObject("range") {
                put("startDate", rangeStart.toString())
                put("recurrenceTimeZone", zoneId)
                val until = rule.until?.let { untilDate(it) }
                when {
                    rule.count != null -> { put("type", "numbered"); put("numberOfOccurrences", rule.count) }
                    until != null -> { put("type", "endDate"); put("endDate", until.toString()) }
                    else -> put("type", "noEnd")
                }
            }
        }
    }

    private fun JsonObjectBuilder.putDays(days: List<DayOfWeek>) {
        putJsonArray("daysOfWeek") { days.distinct().forEach { add(it.name.lowercase()) } }
    }

    private fun JsonObjectBuilder.monthly(rule: RRule, start: LocalDate) {
        val relative = relative(rule)
        if (relative != null) {
            put("type", "relativeMonthly")
            putDays(relative.second)
            put("index", relative.first)
        } else {
            val day = rule.byMonthDay.singleOrNull() ?: if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) start.dayOfMonth else throw UnsupportedRecurrenceException("monthly days")
            if (day < 1) throw UnsupportedRecurrenceException("month day $day")
            put("type", "absoluteMonthly")
            put("dayOfMonth", day)
        }
    }

    private fun JsonObjectBuilder.yearly(rule: RRule, start: LocalDate) {
        val month = rule.byMonth.singleOrNull() ?: if (rule.byMonth.isEmpty()) start.monthValue else throw UnsupportedRecurrenceException("several months")
        val relative = relative(rule)
        if (relative != null) {
            put("type", "relativeYearly")
            putDays(relative.second)
            put("index", relative.first)
        } else {
            val day = rule.byMonthDay.singleOrNull() ?: if (rule.byMonthDay.isEmpty()) start.dayOfMonth else throw UnsupportedRecurrenceException("yearly days")
            if (day < 1) throw UnsupportedRecurrenceException("month day $day")
            put("type", "absoluteYearly")
            put("dayOfMonth", day)
        }
        put("month", month)
    }

    /** "The second Tuesday" as Graph words it (index name and weekdays), or null for a rule that is not relative. */
    private fun relative(rule: RRule): Pair<String, List<DayOfWeek>>? {
        if (rule.byDay.isEmpty()) return null
        val position = rule.bySetPos.singleOrNull() ?: rule.byDay.singleOrNull()?.ordinal?.takeIf { it != 0 } ?: return null
        val name = INDEXES.firstOrNull { it.second == position }?.first ?: throw UnsupportedRecurrenceException("position $position")
        return name to rule.byDay.map { it.day }
    }

    private fun untilDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    // --- Writing: the app's event (as an iCalendar component) as Graph's JSON ---

    private fun JsonObjectBuilder.moment(key: String, millis: Long, allDay: Boolean, zone: ZoneId) {
        putJsonObject(key) {
            if (allDay) {
                put("dateTime", LocalDate.ofEpochDay(Math.floorDiv(millis, DAY_MS)).atStartOfDay().toString() + ":00")
                put("timeZone", "UTC")
            } else {
                put("dateTime", LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).withNano(0).toString().let { if (it.length == 16) "$it:00" else it })
                put("timeZone", if (TimeZones.isUtc(zone)) "UTC" else zone.id)
            }
        }
    }

    /**
     * The JSON of [ical] for an insert or a patch. An occurrence (it has a recurrence id) is sent without repeat rule. A series
     * carries the pattern of its RRULE; [reminders] become the one reminder Outlook has.
     */
    fun toJson(ical: ICalEvent, reminders: List<Int>): JsonObject = buildJsonObject {
        val zone = if (ical.allDay) ZoneOffset.UTC else TimeZones.resolve(ical.timeZone)
        put("subject", ical.summary)
        putJsonObject("body") {
            put("contentType", "text")
            put("content", ical.description)
        }
        putJsonObject("location") { put("displayName", ical.location) }
        put("isAllDay", ical.allDay)
        moment("start", ical.start, ical.allDay, zone)
        moment("end", ical.end, ical.allDay, zone)
        val minutes = reminders.minOrNull()
        put("isReminderOn", minutes != null)
        if (minutes != null) put("reminderMinutesBeforeStart", minutes)
        if (ical.recurrenceId == null) {
            val start = Instant.ofEpochMilli(ical.start).atZone(zone).toLocalDate()
            val zoneId = if (TimeZones.isUtc(zone)) "UTC" else zone.id
            if (ical.rrule != null) put("recurrence", rruleToRecurrence(ical.rrule, start, zoneId)) else put("recurrence", JsonPrimitive(null as String?))
        }
    }

    /** What Graph answered to a write: the id, the version, the UID and the time. */
    fun written(json: JsonObject): RemoteWrite? {
        val id = json.str("id") ?: return null
        return RemoteWrite(id, json.str("iCalUId"), json.str("@odata.etag"), instant(json.str("lastModifiedDateTime")) ?: 0L)
    }
}
