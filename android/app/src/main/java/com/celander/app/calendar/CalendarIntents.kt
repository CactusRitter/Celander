package com.celander.app.calendar

import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.recurrence.RRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An event of the phone's calendars another app asks to show or edit, by its row id in the calendar
 * provider; [beginMillis] is the start of the occurrence it means, if it says.
 */
data class EventLink(val providerId: Long, val beginMillis: Long?, val edit: Boolean)

/**
 * What other apps ask "the calendar app" for, in the form Android's calendar intents use: a new
 * event (a date tapped in an e-mail or a message, "Add to calendar" on a web page), a day to show
 * (`content://com.android.calendar/time/<millis>`) or an event to show or edit
 * (`content://com.android.calendar/events/<id>`).
 */
object CalendarIntents {
    /** The editor request for an [Intent.ACTION_INSERT] of an event, or null for anything else. */
    fun insertRequest(intent: Intent): EditorRequest.InsertEvent? {
        if (intent.action != Intent.ACTION_INSERT) return null
        val type = intent.type ?: intent.data?.let { if (it.authority == CalendarContract.AUTHORITY) EVENTS_DIR else null }
        if (type != EVENTS_DIR && type != EVENT_ITEM) return null
        val extras = intent.extras
        return EditorRequest.InsertEvent(
            beginMillis = extras.long(CalendarContract.EXTRA_EVENT_BEGIN_TIME),
            endMillis = extras.long(CalendarContract.EXTRA_EVENT_END_TIME),
            allDay = extras.flag(CalendarContract.EXTRA_EVENT_ALL_DAY),
            title = extras.text(CalendarContract.Events.TITLE),
            location = extras.text(CalendarContract.Events.EVENT_LOCATION),
            description = extras.text(CalendarContract.Events.DESCRIPTION),
            rrule = extras.text(CalendarContract.Events.RRULE).ifBlank { null },
        )
    }

    /** The day an [Intent.ACTION_VIEW] of a calendar time asks to show, as epoch day in [zone]. */
    fun viewDay(intent: Intent, zone: ZoneId): Long? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.authority != CalendarContract.AUTHORITY || uri.pathSegments.firstOrNull() != TIME_PATH) return null
        val millis = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
    }

    /** The event an [Intent.ACTION_VIEW] or [Intent.ACTION_EDIT] of `content://com.android.calendar/events/<id>` means. */
    fun eventLink(intent: Intent): EventLink? {
        val edit = when (intent.action) {
            Intent.ACTION_VIEW -> false
            Intent.ACTION_EDIT -> true
            else -> return null
        }
        val uri = intent.data ?: return null
        val segments = uri.pathSegments
        if (uri.authority != CalendarContract.AUTHORITY || segments.size != 2 || segments[0] != EVENTS_PATH) return null
        val id = segments[1].toLongOrNull()?.takeIf { it in 0 until SourceIds.DEVICE_BASE } ?: return null
        // A begin time no calendar can hold (e.g. Long.MAX_VALUE) is ignored rather than overflowing later.
        val begin = intent.extras.long(CalendarContract.EXTRA_EVENT_BEGIN_TIME)?.takeIf { it in -MAX_LINK_MILLIS..MAX_LINK_MILLIS }
        return EventLink(id, begin, edit)
    }

    /**
     * Key of the occurrence of [event] a link starting at [beginMillis] means: the occurrence that
     * starts then (moved ones by their new start), else the next one that has not ended at [now],
     * else the first. Single events have just one.
     */
    fun occurrenceKey(event: Event, exceptions: List<EventException>, beginMillis: Long?, now: Instant, zone: ZoneId): Long {
        val first = event.timing.occurrenceKey
        val rule = event.rrule?.let(RRule::parse) ?: return first
        val cancelled = exceptions.filter { it.cancelled }.map { it.originalStart }.toSet()
        val changed = exceptions.filter { !it.cancelled }.associateBy { it.originalStart }
        if (beginMillis != null) {
            changed.values.firstOrNull { e -> e.timing?.let { startsAt(it, beginMillis, zone) } == true }?.let { return it.originalStart }
            for (key in keyCandidates(event.timing, beginMillis, zone)) {
                if (key in cancelled) continue
                if (key in changed || isSeriesKey(event, rule, cancelled, key)) return key
            }
        }
        val keys = OccurrenceCalculator.seriesKeys(event, rule, cancelled, now, now.plus(NEXT_WINDOW), limit = NEXT_LIMIT)
        return keys.firstOrNull { key ->
            val timing = changed[key]?.timing ?: OccurrenceCalculator.timingAt(event.timing, key)
            timing.endInstant(zone).isAfter(now)
        } ?: first
    }

    // All-day instances begin at midnight UTC; some apps send local midnight instead.
    private fun keyCandidates(timing: EventTiming, beginMillis: Long, zone: ZoneId): List<Long> = when (timing) {
        is EventTiming.Timed -> listOf(beginMillis)
        is EventTiming.AllDay -> listOf(
            Math.floorDiv(beginMillis, DAY_MS),
            Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalDate().toEpochDay(),
        ).distinct()
    }

    private fun startsAt(timing: EventTiming, beginMillis: Long, zone: ZoneId): Boolean = when (timing) {
        is EventTiming.Timed -> timing.start.toEpochMilli() == beginMillis
        is EventTiming.AllDay -> timing.startDate.toEpochDay() in keyCandidates(timing, beginMillis, zone)
    }

    private fun isSeriesKey(event: Event, rule: RRule, cancelled: Set<Long>, key: Long): Boolean {
        val at = when (event.timing) {
            is EventTiming.Timed -> Instant.ofEpochMilli(key)
            is EventTiming.AllDay -> LocalDate.ofEpochDay(key).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return key in OccurrenceCalculator.seriesKeys(event, rule, cancelled, at, at.plusMillis(1))
    }

    // Apps send these as Long, Int or text; all-day as Boolean or "1".
    @Suppress("DEPRECATION")
    private fun Bundle?.long(key: String): Long? = when (val value = this?.get(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun Bundle?.flag(key: String): Boolean = when (val value = this?.get(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun Bundle?.text(key: String): String = this?.get(key)?.toString()?.trim().orEmpty()

    private const val EVENTS_DIR = "vnd.android.cursor.dir/event"
    private const val EVENT_ITEM = "vnd.android.cursor.item/event"
    private const val TIME_PATH = "time"
    private const val EVENTS_PATH = "events"
    private const val DAY_MS = 86_400_000L

    /** About 10,000 years either side of 1970: far beyond any calendar, far from overflowing. */
    private const val MAX_LINK_MILLIS = 10_000L * 366 * DAY_MS
    private val NEXT_WINDOW: Duration = Duration.ofDays(800)
    private const val NEXT_LIMIT = 200
}
