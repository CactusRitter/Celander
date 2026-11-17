package com.celander.app.core.device

import com.celander.app.core.ical.ICalDates
import com.celander.app.core.ical.TimeZones
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventAccess
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.ReadOnlyReason
import com.celander.app.core.model.SeriesCapabilities
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.RRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of `CalendarContract.Calendars`, as the cursor delivers it. */
data class CalendarRow(
    val id: Long,
    val accountName: String? = null,
    val accountType: String? = null,
    val syncId: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val color: Int? = null,
    val accessLevel: Int? = null,
    val ownerAccount: String? = null,
    val visible: Int? = null,
    val isPrimary: Int? = null,
    val maxReminders: Int? = null,
    val allowedReminders: String? = null,
)

/** One row of `CalendarContract.Instances`, as the cursor delivers it. */
data class InstanceRow(
    val eventId: Long?,
    val begin: Long?,
    val end: Long?,
    val allDay: Int? = null,
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val eventTimezone: String? = null,
    val calendarId: Long?,
    val rrule: String? = null,
    val originalId: Long? = null,
    val originalInstanceTime: Long? = null,
    val originalAllDay: Int? = null,
    val displayColor: Int? = null,
    val calendarColor: Int? = null,
)

/** One row of `CalendarContract.Events` (a master), including the joined calendar columns. */
data class EventRow(
    val id: Long,
    val calendarId: Long?,
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val dtStart: Long?,
    val dtEnd: Long? = null,
    val duration: String? = null,
    val allDay: Int? = null,
    val eventTimezone: String? = null,
    val rrule: String? = null,
    val rdate: String? = null,
    val exrule: String? = null,
    val exdate: String? = null,
    val uid: String? = null,
    val displayColor: Int? = null,
    val calendarColor: Int? = null,
    val organizer: String? = null,
    val isOrganizer: Int? = null,
    val guestsCanModify: Int? = null,
    val hasAttendeeData: Int? = null,
    val ownerAccount: String? = null,
    val accessLevel: Int? = null,
    val deleted: Int? = null,
)

/** One `CalendarContract.Events` row that modifies or cancels an occurrence of a master. */
data class ExceptionRow(
    val id: Long,
    val originalInstanceTime: Long?,
    val originalAllDay: Int? = null,
    val dtStart: Long? = null,
    val dtEnd: Long? = null,
    val duration: String? = null,
    val allDay: Int? = null,
    val eventTimezone: String? = null,
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val status: Int? = null,
    val deleted: Int? = null,
)

/** One row of `CalendarContract.Reminders`. */
data class ReminderRow(val eventId: Long?, val minutes: Int?, val method: Int?)

/** A provider event loaded for the detail sheet and the editor; its reminders are in [event]. */
data class DeviceEvent(
    val event: Event,
    val exceptions: List<EventException>,
    val access: EventAccess,
    val capabilities: SeriesCapabilities,
)

/**
 * Turns calendar provider rows into Celander's model. Ids are moved into the [SourceIds] device
 * range; colors are mapped to palette keys by the caller's `colorKeyOf`, so this stays free of the
 * design system. All-day values are UTC midnights in the provider and never touch the device zone.
 */
object DeviceMapping {
    private const val DAY_MS = 86_400_000L
    private const val STATUS_CANCELED = 2
    private const val ACCESS_CONTRIBUTOR = 500
    private const val METHOD_DEFAULT = 0
    private const val METHOD_ALERT = 1

    /** Key that identifies a provider calendar across account re-adds and restores. */
    fun stableKey(row: CalendarRow): String =
        "${row.accountType}|${row.accountName}|${row.syncId ?: row.name ?: "id:${row.id}"}"

    /**
     * [prefVisible] and [prefColorKey] are Celander's own overrides; the provider's VISIBLE and
     * color are never written back. [canWrite] is the WRITE_CALENDAR permission.
     */
    fun calendar(
        row: CalendarRow,
        canWrite: Boolean,
        prefVisible: Boolean?,
        prefColorKey: String?,
        colorKeyOf: (Int) -> String,
    ): CalendarInfo = CalendarInfo(
        id = SourceIds.device(row.id),
        name = row.displayName ?: row.name.orEmpty(),
        colorKey = prefColorKey ?: colorKeyOf(row.color ?: 0),
        visible = prefVisible ?: (row.visible == 1),
        source = CalendarSource.DEVICE,
        writable = canWrite && (row.accessLevel ?: 0) >= ACCESS_CONTRIBUTOR,
        account = row.accountName,
        accountType = row.accountType,
        maxReminders = row.maxReminders,
    )

    /** Accounts that have a primary calendar first, then by account; primary first, then by name. */
    fun sorted(rows: List<CalendarRow>): List<CalendarRow> {
        val withPrimary = rows.filter { it.isPrimary == 1 }.map { it.accountName }.toSet()
        return rows.sortedWith(
            compareBy<CalendarRow> { it.accountName !in withPrimary }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.accountName.orEmpty() }
                .thenBy { it.isPrimary != 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: it.name.orEmpty() },
        )
    }

    /**
     * Occurrences of [rows]; rows of unknown calendars and malformed rows are skipped.
     * [calendars] is keyed by Celander id, [masterRrules] by provider id of the master.
     */
    fun occurrences(
        rows: List<InstanceRow>,
        calendars: Map<Long, CalendarInfo>,
        masterRrules: Map<Long, String?>,
        colorKeyOf: (Int) -> String,
    ): List<EventOccurrence> = rows.mapNotNull { row ->
        runCatching {
            val calendar = calendars[SourceIds.device(requireNotNull(row.calendarId))] ?: return@runCatching null
            occurrence(row, row.originalId?.let { masterRrules[it] }, calendar, colorKeyOf)
        }.getOrNull()
    }

    /**
     * The occurrence of one instance row. [masterRrule] is the master's RRULE for a modified
     * instance, whose own row has none. Throws on rows missing required columns.
     */
    fun occurrence(
        row: InstanceRow,
        masterRrule: String?,
        calendar: CalendarInfo,
        colorKeyOf: (Int) -> String,
    ): EventOccurrence {
        val masterId = row.originalId ?: requireNotNull(row.eventId) { "no event id" }
        val begin = requireNotNull(row.begin) { "no begin" }
        val masterAllDay = if (row.originalId != null) row.originalAllDay == 1 else row.allDay == 1
        val key = occurrenceKey(row.originalInstanceTime ?: begin, masterAllDay)
        val timing = timing(begin, row.end ?: begin, row.allDay == 1, row.eventTimezone)
        val title = row.title.orEmpty()
        val location = row.location.orEmpty()
        val description = row.description.orEmpty()
        val event = Event(
            id = SourceIds.device(masterId),
            uid = "device:$masterId",
            calendarId = SourceIds.device(requireNotNull(row.calendarId) { "no calendar" }),
            title = title,
            location = location,
            description = description,
            colorKey = colorKey(row.displayColor, row.calendarColor, colorKeyOf),
            timing = timing,
            rrule = row.rrule ?: masterRrule,
        )
        return EventOccurrence(
            event = event,
            calendar = calendar,
            originalStart = key,
            title = title,
            location = location,
            description = description,
            timing = timing,
            isException = row.originalId != null,
        )
    }

    /**
     * The master [row] with its exception rows and reminders, or null when it is deleted.
     * Malformed exception rows are skipped. [canWrite] is the WRITE_CALENDAR permission.
     */
    fun event(
        row: EventRow,
        exceptions: List<ExceptionRow>,
        reminders: List<ReminderRow>,
        canWrite: Boolean,
        colorKeyOf: (Int) -> String,
    ): DeviceEvent? {
        if (row.deleted == 1) return null
        val start = requireNotNull(row.dtStart) { "no start" }
        val allDay = row.allDay == 1
        val masterId = SourceIds.device(row.id)
        val event = Event(
            id = masterId,
            uid = row.uid ?: "device:${row.id}",
            calendarId = SourceIds.device(requireNotNull(row.calendarId) { "no calendar" }),
            title = row.title.orEmpty(),
            location = row.location.orEmpty(),
            description = row.description.orEmpty(),
            colorKey = colorKey(row.displayColor, row.calendarColor, colorKeyOf),
            timing = timing(start, end(start, row.dtEnd, row.duration), allDay, row.eventTimezone),
            rrule = row.rrule,
            reminders = reminders
                .filter { it.method == METHOD_DEFAULT || it.method == METHOD_ALERT }
                .mapNotNull { it.minutes }
                .distinct()
                .sorted(),
        )
        val mapped = exceptions.mapNotNull { runCatching { exception(it, masterId, allDay) }.getOrNull() }
        return DeviceEvent(event, mapped, access(row, canWrite), capabilities(row, mapped))
    }

    fun access(row: EventRow, canWrite: Boolean): EventAccess {
        val level = row.accessLevel ?: 0
        val organizer = row.isOrganizer == 1 ||
            (row.isOrganizer == null && row.organizer != null && row.organizer.equals(row.ownerAccount, ignoreCase = true)) ||
            row.hasAttendeeData == 0
        val writable = canWrite && level >= ACCESS_CONTRIBUTOR
        val canEdit = writable && (organizer || row.guestsCanModify == 1)
        val canDelete = writable && organizer
        val reason = when {
            canEdit && canDelete -> null
            !canWrite -> ReadOnlyReason.NO_PERMISSION
            level < ACCESS_CONTRIBUTOR -> ReadOnlyReason.CALENDAR
            else -> ReadOnlyReason.GUEST
        }
        return EventAccess(canEdit = canEdit, canDelete = canDelete, reason = reason, organizer = row.organizer)
    }

    /** Which series edits the provider row can take without losing RDATE, EXRULE or unknown rule parts. */
    fun capabilities(row: EventRow, exceptions: List<EventException>): SeriesCapabilities {
        val rule = row.rrule?.let(RRule::parse)
        val canCap = rule != null && row.rdate == null
        val canSplit = canCap && row.exrule == null &&
            (row.exdate == null || ProviderExDates.parse(row.exdate) != null) &&
            !(rule?.count != null && rule.extras.isNotEmpty())
        val canShiftAll = canSplit && row.exdate == null && exceptions.none { !it.cancelled }
        return SeriesCapabilities(canSplit = canSplit, canCap = canCap, canShiftAll = canShiftAll)
    }

    /** Key of the occurrence an exception replaces: epoch day for all-day masters, else epoch millis. */
    fun occurrenceKey(originalInstanceTime: Long, masterAllDay: Boolean): Long =
        if (masterAllDay) Math.floorDiv(originalInstanceTime, DAY_MS) else originalInstanceTime

    /** All-day: UTC days with the exclusive [end] made inclusive; timed: instants in the event zone. */
    fun timing(begin: Long, end: Long, allDay: Boolean, timeZone: String?): EventTiming = if (allDay) {
        val startDay = Math.floorDiv(begin, DAY_MS)
        val endDay = maxOf(startDay, Math.floorDiv(end - 1, DAY_MS))
        EventTiming.AllDay(LocalDate.ofEpochDay(startDay), LocalDate.ofEpochDay(endDay))
    } else {
        EventTiming.Timed(Instant.ofEpochMilli(begin), Instant.ofEpochMilli(maxOf(begin, end)), zone(timeZone))
    }

    private fun exception(row: ExceptionRow, masterId: Long, masterAllDay: Boolean): EventException? {
        if (row.deleted == 1) return null
        val original = requireNotNull(row.originalInstanceTime) { "no original instance time" }
        val key = occurrenceKey(original, row.originalAllDay?.let { it == 1 } ?: masterAllDay)
        if (row.status == STATUS_CANCELED) {
            return EventException(id = SourceIds.device(row.id), eventId = masterId, originalStart = key, cancelled = true)
        }
        return EventException(
            id = SourceIds.device(row.id),
            eventId = masterId,
            originalStart = key,
            title = row.title,
            location = row.location,
            description = row.description,
            timing = row.dtStart?.let { timing(it, end(it, row.dtEnd, row.duration), row.allDay == 1, row.eventTimezone) },
        )
    }

    /** Like the provider's instance expansion: a readable DURATION wins over DTEND when a row has both. */
    private fun end(start: Long, dtEnd: Long?, duration: String?): Long =
        duration?.let(::duration)?.let { start + it.toMillis() } ?: dtEnd ?: start

    /** RFC 5545 durations, plus the provider's own form without `T` such as `P3600S`. */
    fun duration(value: String): Duration? {
        val v = value.trim().uppercase()
        return ICalDates.parseDuration(v)
            ?: if ('T' !in v) ICalDates.parseDuration(v.replaceFirst("P", "PT")) else null
    }

    private fun colorKey(display: Int?, calendar: Int?, colorKeyOf: (Int) -> String): String? =
        if (display != null && display != calendar) colorKeyOf(display) else null

    private fun zone(timeZone: String?): ZoneId =
        timeZone?.takeIf(String::isNotBlank)?.let(TimeZones::resolve) ?: ZoneId.systemDefault()
}
