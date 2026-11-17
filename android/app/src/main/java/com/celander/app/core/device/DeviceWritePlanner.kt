package com.celander.app.core.device

import com.celander.app.core.device.ProviderColumns as C
import com.celander.app.core.ical.ICalDates
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.recurrence.SeriesEdits
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * One write to the calendar provider. Ids are provider row ids (not Celander's device ids); values
 * map [ProviderColumns] names to Long, Int, String or null.
 */
sealed interface ProviderOp {
    /** Inserts an event row into `Events.CONTENT_URI`. */
    data class InsertEvent(val values: Map<String, Any?>) : ProviderOp

    /** Updates `Events/{id}` with just the columns that changed. */
    data class UpdateEvent(val id: Long, val values: Map<String, Any?>) : ProviderOp

    /** Deletes `Events/{id}`; the provider marks it deleted until the sync adapter removes it. */
    data class DeleteEvent(val id: Long) : ProviderOp

    /** Inserts an exception of [masterId] through `Events.CONTENT_EXCEPTION_URI/{masterId}`. */
    data class InsertException(val masterId: Long, val values: Map<String, Any?>) : ProviderOp

    /** Inserts one reminder row per entry of [minutes], all with [method], for the event [ref]. */
    data class InsertReminders(val ref: EventRef, val minutes: List<Int>, val method: Int) : ProviderOp

    /** Deletes the reminders of [eventId] with one of [methods]; e-mail reminders stay by default. */
    data class DeleteReminders(
        val eventId: Long,
        val methods: Set<Int> = setOf(C.METHOD_DEFAULT, C.METHOD_ALERT),
    ) : ProviderOp
}

/** The event a reminder insert belongs to. */
sealed interface EventRef {
    /** An event row that already exists. */
    data class Existing(val id: Long) : EventRef

    /** The row inserted by the op at [index] of the same batch (a back-reference). */
    data class BackRef(val index: Int) : EventRef
}

/** Reminder limits of a provider calendar (`MAX_REMINDERS`, `ALLOWED_REMINDERS` such as `"0,1,2"`). */
data class ReminderPolicy(val maxReminders: Int? = null, val allowedReminders: String? = null) {
    /** `METHOD_ALERT` when the calendar allows it, otherwise `METHOD_DEFAULT`. */
    val method: Int
        get() = if (allowedReminders?.split(',')?.any { it.trim() == C.METHOD_ALERT.toString() } == true) C.METHOD_ALERT else C.METHOD_DEFAULT

    /** Minutes the provider can store: no negative offsets, no duplicates, sorted, capped. */
    fun normalize(minutes: List<Int>): List<Int> = minutes.filter { it >= 0 }.distinct().sorted().take(maxReminders ?: DEFAULT_MAX_REMINDERS)

    companion object {
        const val DEFAULT_MAX_REMINDERS = 5
    }
}

/**
 * A provider event as loaded for editing: the mapped [master] (device ids), its [exceptions] (device
 * ids, keyed by occurrence key), the raw [columns] of the master row that updates compare against,
 * and the master's EXDATE entries as occurrence keys. A column missing from [columns] counts as
 * changed.
 */
data class DeviceEventState(
    val master: Event,
    val exceptions: List<EventException> = emptyList(),
    val columns: Map<String, Any?> = emptyMap(),
    val exDateKeys: List<Long> = emptyList(),
) {
    val providerId: Long get() = SourceIds.providerId(master.id)
}

/**
 * Result of "this and following": [ops] run as one batch; when it inserted a new series (at
 * [seriesIndex]), [followUp] re-creates its modified occurrences in a second batch.
 */
data class FollowingPlan(
    val ops: List<ProviderOp>,
    val seriesIndex: Int? = null,
    val series: Event? = null,
    val seriesExceptions: List<EventException> = emptyList(),
) {
    /** Exception inserts for the new series once the provider assigned it [newMasterId]. */
    fun followUp(newMasterId: Long): List<ProviderOp> {
        val s = series ?: return emptyList()
        return seriesExceptions.map { DeviceWritePlanner.modifiedException(newMasterId, s, it) }
    }
}

/**
 * Turns Celander edits of provider events into [ProviderOp]s. Pure: the caller loads the state and
 * runs the ops. Never written: colors, `_sync_id`, `dirty`, `visible`, attendees and `UID_2445`; an
 * existing event never moves to another calendar.
 */
object DeviceWritePlanner {
    private const val DAY_MS = 86_400_000L

    /** The provider assigns UIDs; the new series' uid is never written. */
    private const val NEW_SERIES_UID = ""

    private val TEXT_COLUMNS = setOf(C.TITLE, C.EVENT_LOCATION, C.DESCRIPTION)

    /**
     * Columns the provider re-expands instances from. It does so only when an update carries DTSTART
     * and reads end and rule from that same update, so a change to any of them sends them all.
     */
    private val INSTANCE_COLUMNS = setOf(C.DTSTART, C.DTEND, C.DURATION, C.EVENT_TIMEZONE, C.ALL_DAY, C.RRULE)
    private val PROVIDER_DURATION = Regex("""P(\d+)([SD])""")

    /** A new event in [Event.calendarId] with its reminders. */
    fun insert(event: Event, policy: ReminderPolicy): List<ProviderOp> = buildList {
        add(ProviderOp.InsertEvent(insertValues(event)))
        addReminders(EventRef.BackRef(0), event.reminders, policy)
    }

    /**
     * The whole event [state] becomes [edited]: changed columns, exception rows when it stops
     * recurring, and reminders when their minute set changed.
     */
    fun update(state: DeviceEventState, edited: Event, policy: ReminderPolicy): List<ProviderOp> = buildList {
        val id = state.providerId
        val changed = changed(eventValues(edited), state.columns)
        if (changed.isNotEmpty()) add(ProviderOp.UpdateEvent(id, changed))
        if (state.master.isRecurring && !edited.isRecurring) {
            state.exceptions.forEach { add(ProviderOp.DeleteEvent(SourceIds.providerId(it.id))) }
        }
        addReminderChange(id, state.master.reminders, edited.reminders, policy)
    }

    /** Deletes a single event or a whole series ("all"). */
    fun delete(state: DeviceEventState): List<ProviderOp> = listOf(ProviderOp.DeleteEvent(state.providerId))

    /** "This": the occurrence [key] becomes [edited], updating its exception row if one exists. */
    fun editThis(state: DeviceEventState, key: Long, edited: Event): List<ProviderOp> {
        val existing = state.exceptions.firstOrNull { it.originalStart == key }
        val desired = occurrenceValues(edited.timing, edited.title, edited.location, edited.description)
        if (existing == null) {
            return listOf(ProviderOp.InsertException(state.providerId, originalValues(state.master, key) + desired))
        }
        val master = state.master
        val current = occurrenceValues(
            existing.timing ?: OccurrenceCalculator.timingAt(master.timing, key),
            existing.title ?: master.title,
            existing.location ?: master.location,
            existing.description ?: master.description,
        )
        val changed = changed(desired, current)
        return if (changed.isEmpty()) emptyList() else listOf(ProviderOp.UpdateEvent(SourceIds.providerId(existing.id), changed))
    }

    /**
     * "This", delete: the occurrence [key] gets a cancelled exception. An existing exception row is
     * cancelled, never deleted, since deleting it would bring the occurrence back.
     */
    fun deleteThis(state: DeviceEventState, key: Long): List<ProviderOp> {
        val existing = state.exceptions.firstOrNull { it.originalStart == key }
        val cancel = mapOf(C.STATUS to C.STATUS_CANCELED)
        return when {
            existing == null -> listOf(ProviderOp.InsertException(state.providerId, originalValues(state.master, key) + cancel))
            existing.cancelled -> emptyList()
            else -> listOf(ProviderOp.UpdateEvent(SourceIds.providerId(existing.id), cancel))
        }
    }

    /**
     * "This and following": the master is capped before [key], its exception rows from [key] on are
     * deleted, and [edited] starts a new series whose EXDATE carries the cancelled occurrences
     * (shifted when the time changed). Falls back to [editAll] when [key] is the first occurrence.
     */
    fun editFollowing(state: DeviceEventState, key: Long, edited: Event, policy: ReminderPolicy): FollowingPlan {
        val master = state.master
        val occurrencesBefore = SeriesEdits.occurrencesBefore(master, key)
        val split = SeriesEdits.splitAt(master, key, edited, state.exceptions, occurrencesBefore, NEW_SERIES_UID)
            ?: return FollowingPlan(editAll(state, key, edited, policy))
        val from = SeriesEdits.shiftReference(master, key, state.exceptions)
        val masterExDates = state.exDateKeys.filter { it >= key }.map { exDate ->
            if (edited.timing == from) {
                exDate
            } else {
                SeriesEdits.shift(OccurrenceCalculator.timingAt(master.timing, exDate), from, edited.timing).occurrenceKey
            }
        }
        val series = split.series
        val (cancelled, modified) = split.seriesExceptions.partition { it.cancelled }
        val exDates = (cancelled.map { it.originalStart } + masterExDates).distinct().sorted()
        val seriesValues = insertValues(series).let { values ->
            if (series.isRecurring && exDates.isNotEmpty()) values + (C.EXDATE to formatExDates(exDates, series.allDay)) else values
        }
        val ops = buildList {
            add(ProviderOp.UpdateEvent(state.providerId, instanceValues(split.master)))
            state.exceptions.filter { it.originalStart >= key }.forEach { add(ProviderOp.DeleteEvent(SourceIds.providerId(it.id))) }
            add(ProviderOp.InsertEvent(seriesValues))
            addReminders(EventRef.BackRef(size - 1), series.reminders, policy)
        }
        val seriesIndex = ops.indexOfFirst { it is ProviderOp.InsertEvent }
        return FollowingPlan(ops, seriesIndex, series, if (series.isRecurring) modified else emptyList())
    }

    /**
     * "This and following", delete: the master is deleted when [key] is its first occurrence,
     * otherwise capped before [key] with its exception rows from [key] on deleted.
     */
    fun deleteFollowing(state: DeviceEventState, key: Long): List<ProviderOp> {
        val capped = SeriesEdits.endBefore(state.master, key) ?: return delete(state)
        return buildList {
            add(ProviderOp.UpdateEvent(state.providerId, instanceValues(capped)))
            state.exceptions.filter { it.originalStart >= key }.forEach { add(ProviderOp.DeleteEvent(SourceIds.providerId(it.id))) }
        }
    }

    /**
     * "All": the occurrence [key] was edited into [edited] and the series follows by the same
     * wall-clock shift. Without a time change exceptions stay; with one, the kept cancelled
     * occurrences are re-created at their shifted keys (the old rows no longer match an instance).
     */
    fun editAll(state: DeviceEventState, key: Long, edited: Event, policy: ReminderPolicy): List<ProviderOp> {
        val master = state.master
        val (updated, kept) = SeriesEdits.applyToAll(master, key, edited, state.exceptions)
        val timeChanged = edited.timing != SeriesEdits.shiftReference(master, key, state.exceptions)
        return buildList {
            addAll(update(state, updated, policy))
            // Without a rule update() has removed every exception row already.
            if (!updated.isRecurring) return@buildList
            if (timeChanged) {
                kept.forEach { add(ProviderOp.InsertException(state.providerId, originalValues(updated, it.originalStart) + (C.STATUS to C.STATUS_CANCELED))) }
                return@buildList
            }
            // Rows of occurrences a changed rule no longer produces would stay as stray events.
            val stay = if (SeriesEdits.sameRule(master, edited)) state.exceptions else SeriesEdits.stillInSeries(updated, state.exceptions)
            state.exceptions.filter { it !in stay }.forEach { add(ProviderOp.DeleteEvent(SourceIds.providerId(it.id))) }
            // The occurrence's own earlier change would hide the text just edited for the series.
            if (stay.any { it.originalStart == key && !it.cancelled }) addAll(editThis(state, key, edited))
        }
    }

    /** Insert values of a modified occurrence of [series] for the exception URI of [masterId]. */
    fun modifiedException(masterId: Long, series: Event, exception: EventException): ProviderOp.InsertException {
        val values = originalValues(series, exception.originalStart) + occurrenceValues(
            exception.timing ?: OccurrenceCalculator.timingAt(series.timing, exception.originalStart),
            exception.title ?: series.title,
            exception.location ?: series.location,
            exception.description ?: series.description,
        )
        return ProviderOp.InsertException(masterId, values)
    }

    /**
     * Timing, rule and text columns of [event]: single events store DTEND, recurring ones DURATION;
     * all-day events are stored in UTC with an exclusive end.
     */
    fun eventValues(event: Event): Map<String, Any?> {
        val recurring = event.isRecurring
        val timing = when (val t = event.timing) {
            is EventTiming.Timed -> {
                val start = t.start.toEpochMilli()
                linkedMapOf<String, Any?>(
                    C.DTSTART to start,
                    C.DTEND to if (recurring) null else t.end.toEpochMilli(),
                    C.DURATION to if (recurring) "P${Duration.between(t.start, t.end).seconds}S" else null,
                    C.EVENT_TIMEZONE to t.zone.id,
                    C.ALL_DAY to 0,
                )
            }
            is EventTiming.AllDay -> {
                val days = ChronoUnit.DAYS.between(t.startDate, t.endDate) + 1
                linkedMapOf<String, Any?>(
                    C.DTSTART to t.startDate.toEpochDay() * DAY_MS,
                    C.DTEND to if (recurring) null else (t.endDate.toEpochDay() + 1) * DAY_MS,
                    C.DURATION to if (recurring) "P${days}D" else null,
                    C.EVENT_TIMEZONE to C.UTC,
                    C.ALL_DAY to 1,
                )
            }
        }
        return timing + mapOf(
            C.RRULE to event.rrule,
            C.TITLE to event.title,
            C.EVENT_LOCATION to event.location,
            C.DESCRIPTION to event.description,
        )
    }

    private fun insertValues(event: Event): Map<String, Any?> =
        eventValues(event) + (C.CALENDAR_ID to SourceIds.providerId(event.calendarId))

    /** Columns of one occurrence row: always DTEND, never a rule. */
    private fun occurrenceValues(timing: EventTiming, title: String, location: String, description: String): Map<String, Any?> {
        val single = eventValues(Event(uid = NEW_SERIES_UID, calendarId = 0, title = title, location = location, description = description, timing = timing))
        return single - C.DURATION - C.RRULE
    }

    /**
     * Identifies the occurrence [key] of [master]; all-day keys are days, the provider wants millis.
     * ORIGINAL_ALL_DAY is not sent: the provider takes it from the master and rejects it in exception
     * inserts ("Exceptions can't overwrite originalAllDay").
     */
    private fun originalValues(master: Event, key: Long): Map<String, Any?> = mapOf<String, Any?>(
        C.ORIGINAL_INSTANCE_TIME to if (master.allDay) key * DAY_MS else key,
    )

    /** EXDATE in the provider's UTC list form `yyyyMMdd'T'HHmmss'Z'`, comma separated. */
    private fun formatExDates(keys: List<Long>, allDay: Boolean): String =
        keys.joinToString(",") { ICalDates.formatUtc(if (allDay) it * DAY_MS else it) }

    private fun MutableList<ProviderOp>.addReminders(ref: EventRef, minutes: List<Int>, policy: ReminderPolicy) {
        val normalized = policy.normalize(minutes)
        if (normalized.isNotEmpty()) add(ProviderOp.InsertReminders(ref, normalized, policy.method))
    }

    private fun MutableList<ProviderOp>.addReminderChange(id: Long, current: List<Int>, wanted: List<Int>, policy: ReminderPolicy) {
        if (policy.normalize(current) == policy.normalize(wanted)) return
        add(ProviderOp.DeleteReminders(id))
        addReminders(EventRef.Existing(id), wanted, policy)
    }

    /** Columns of [desired] that differ from [current], with all [INSTANCE_COLUMNS] if one of them does. */
    private fun changed(desired: Map<String, Any?>, current: Map<String, Any?>): Map<String, Any?> {
        val changed = desired.filter { (column, value) -> column !in current || !same(column, value, current[column]) }
        return if (changed.keys.any { it in INSTANCE_COLUMNS }) changed + desired.filterKeys { it in INSTANCE_COLUMNS } else changed
    }

    /** The [INSTANCE_COLUMNS] of [event], for rule changes that leave the rest of the row alone. */
    private fun instanceValues(event: Event): Map<String, Any?> = eventValues(event).filterKeys { it in INSTANCE_COLUMNS }

    /** Also accepts the provider's own `P<n>S` and `P<n>D`, which are not RFC 5545 durations. */
    private fun durationOf(value: String): Duration? {
        PROVIDER_DURATION.matchEntire(value.trim().uppercase())?.let { m ->
            val n = m.groupValues[1].toLong()
            return if (m.groupValues[2] == "S") Duration.ofSeconds(n) else Duration.ofDays(n)
        }
        return ICalDates.parseDuration(value)
    }

    /** Equality as the provider means it: numbers by value, empty text as null, durations by length. */
    private fun same(column: String, a: Any?, b: Any?): Boolean = when {
        column in TEXT_COLUMNS -> a?.toString().orEmpty() == b?.toString().orEmpty()
        column == C.DURATION && a is String && b is String -> a == b || durationOf(a)?.let { it == durationOf(b) } == true
        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }
}
