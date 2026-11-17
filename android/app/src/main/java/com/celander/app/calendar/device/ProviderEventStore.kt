package com.celander.app.calendar.device

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.celander.app.core.device.DeviceEventState
import com.celander.app.core.device.DeviceWritePlanner
import com.celander.app.core.device.EventRef
import com.celander.app.core.device.ProviderColumns
import com.celander.app.core.device.ProviderExDates
import com.celander.app.core.device.ProviderOp
import com.celander.app.core.device.ReminderPolicy
import com.celander.app.core.di.IoDispatcher
import com.celander.app.core.model.Event
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.SeriesScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** [DeviceEventStore] that runs [DeviceWritePlanner]'s operations as provider batches. */
@Singleton
class ProviderEventStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: DeviceCalendarSource,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceEventStore {
    override suspend fun create(event: Event): Long = withContext(io) {
        val calendarId = SourceIds.providerId(event.calendarId)
        val ops = DeviceWritePlanner.insert(event, policy(calendarId))
        val results = apply(ops, calendarName(event.calendarId))
        SourceIds.device(ContentUris.parseId(requireNotNull(results.first().uri)))
    }

    override suspend fun update(edited: Event) {
        withContext(io) {
            val state = state(edited.id)
            apply(DeviceWritePlanner.update(state, edited.copy(calendarId = state.master.calendarId), policyOf(state)), calendarName(state.master.calendarId))
        }
    }

    override suspend fun save(masterId: Long, key: Long, edited: Event, scope: SeriesScope) {
        withContext(io) {
            val state = state(masterId)
            val name = calendarName(state.master.calendarId)
            val event = edited.copy(id = masterId, calendarId = state.master.calendarId)
            when (scope) {
                SeriesScope.THIS -> apply(DeviceWritePlanner.editThis(state, key, event), name)
                SeriesScope.ALL -> apply(DeviceWritePlanner.editAll(state, key, event, policyOf(state)), name)
                SeriesScope.THIS_AND_FOLLOWING -> {
                    val plan = DeviceWritePlanner.editFollowing(state, key, event, policyOf(state))
                    val results = apply(plan.ops, name)
                    val newId = plan.seriesIndex?.let { index -> results.getOrNull(cpoIndex(plan.ops, index))?.uri?.let(ContentUris::parseId) }
                    // The new series is saved; its changed occurrences come second and may fail alone.
                    if (newId != null) {
                        runCatching { apply(plan.followUp(newId), name) }.onFailure { Log.w(TAG, "Could not copy changed occurrences", it) }
                    }
                }
            }
        }
    }

    override suspend fun delete(eventId: Long, key: Long, scope: SeriesScope) {
        withContext(io) {
            val state = state(eventId)
            val ops = when {
                !state.master.isRecurring || scope == SeriesScope.ALL -> DeviceWritePlanner.delete(state)
                scope == SeriesScope.THIS -> DeviceWritePlanner.deleteThis(state, key)
                else -> DeviceWritePlanner.deleteFollowing(state, key)
            }
            apply(ops, calendarName(state.master.calendarId))
        }
    }

    /** The master as the planner needs it: mapped event, raw columns and EXDATE keys. */
    private suspend fun state(eventId: Long): DeviceEventState {
        val loaded = source.getEvent(eventId, includePendingDeletes = true) ?: throw DeviceWriteException(calendarNameOrEmpty(null))
        val columns = try {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, SourceIds.providerId(eventId)),
                STATE_COLUMNS,
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    STATE_COLUMNS.indices.associate { i ->
                        STATE_COLUMNS[i] to when {
                            c.isNull(i) -> null
                            STATE_COLUMNS[i] in NUMBER_COLUMNS -> c.getLong(i)
                            else -> c.getString(i)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            throw DeviceWriteException(calendarName(loaded.event.calendarId), e)
        } ?: throw DeviceWriteException(calendarName(loaded.event.calendarId))
        val exDate = columns[ProviderColumns.EXDATE] as? String
        return DeviceEventState(
            master = loaded.event,
            exceptions = loaded.exceptions,
            columns = columns - ProviderColumns.EXDATE,
            exDateKeys = exDate?.let { ProviderExDates.keys(it, loaded.event.allDay) }.orEmpty(),
        )
    }

    private fun policyOf(state: DeviceEventState) = policy(SourceIds.providerId(state.master.calendarId))

    /** Reminder limits of the calendar; the provider's defaults if they cannot be read. */
    private fun policy(providerCalendarId: Long): ReminderPolicy = try {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, providerCalendarId),
            arrayOf(CalendarContract.Calendars.MAX_REMINDERS, CalendarContract.Calendars.ALLOWED_REMINDERS),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) ReminderPolicy(if (c.isNull(0)) null else c.getInt(0), c.getString(1)) else null }
    } catch (_: SecurityException) {
        null
    } ?: ReminderPolicy()

    private suspend fun calendarName(calendarId: Long): String = calendarNameOrEmpty(calendarId)

    private suspend fun calendarNameOrEmpty(calendarId: Long?): String {
        val calendars = runCatching { source.calendars.first() }.getOrNull().orEmpty()
        return calendars.firstOrNull { it.id == calendarId }?.name.orEmpty()
    }

    /** Runs [ops] as one batch; a failure throws [DeviceWriteException] naming [calendar]. */
    private fun apply(ops: List<ProviderOp>, calendar: String): Array<ContentProviderResult> {
        if (ops.isEmpty()) return emptyArray()
        val batch = ArrayList<ContentProviderOperation>()
        val firstCpo = IntArray(ops.size)
        ops.forEachIndexed { index, op ->
            firstCpo[index] = batch.size
            when (op) {
                is ProviderOp.InsertEvent -> batch += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(op.values.toContentValues()).build()
                is ProviderOp.UpdateEvent -> batch += ContentProviderOperation.newUpdate(eventUri(op.id)).withValues(op.values.toContentValues()).withExpectedCount(1).build()
                is ProviderOp.DeleteEvent -> batch += ContentProviderOperation.newDelete(eventUri(op.id)).build()
                is ProviderOp.InsertException -> batch += ContentProviderOperation
                    .newInsert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, op.masterId))
                    .withValues(op.values.toContentValues())
                    .build()
                is ProviderOp.InsertReminders -> op.minutes.forEach { minutes ->
                    val builder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValue(CalendarContract.Reminders.MINUTES, minutes)
                        .withValue(CalendarContract.Reminders.METHOD, op.method)
                    batch += when (val ref = op.ref) {
                        is EventRef.Existing -> builder.withValue(CalendarContract.Reminders.EVENT_ID, ref.id)
                        is EventRef.BackRef -> builder.withValueBackReference(CalendarContract.Reminders.EVENT_ID, firstCpo[ref.index])
                    }.build()
                }
                is ProviderOp.DeleteReminders -> batch += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                    .withSelection(
                        "${CalendarContract.Reminders.EVENT_ID} = ? AND ${CalendarContract.Reminders.METHOD} IN (${op.methods.joinToString(",")})",
                        arrayOf(op.eventId.toString()),
                    )
                    .build()
            }
        }
        return try {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, batch)
        } catch (e: SecurityException) {
            throw DeviceWriteException(calendar, e)
        } catch (e: Exception) {
            throw DeviceWriteException(calendar, e)
        }
    }

    /** Index of the first provider operation of [ops]`[index]` (reminder ops expand to several). */
    private fun cpoIndex(ops: List<ProviderOp>, index: Int): Int =
        ops.take(index).sumOf { if (it is ProviderOp.InsertReminders) it.minutes.size else 1 }

    private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    private fun Map<String, Any?>.toContentValues() = ContentValues().also { values ->
        forEach { (column, value) ->
            when (value) {
                null -> values.putNull(column)
                is Long -> values.put(column, value)
                is Int -> values.put(column, value)
                is String -> values.put(column, value)
                else -> values.put(column, value.toString())
            }
        }
    }

    private companion object {
        const val TAG = "ProviderEventStore"
        val STATE_COLUMNS = arrayOf(
            ProviderColumns.DTSTART,
            ProviderColumns.DTEND,
            ProviderColumns.DURATION,
            ProviderColumns.EVENT_TIMEZONE,
            ProviderColumns.ALL_DAY,
            ProviderColumns.RRULE,
            ProviderColumns.TITLE,
            ProviderColumns.EVENT_LOCATION,
            ProviderColumns.DESCRIPTION,
            ProviderColumns.EXDATE,
        )
        val NUMBER_COLUMNS = setOf(ProviderColumns.DTSTART, ProviderColumns.DTEND, ProviderColumns.ALL_DAY)
    }
}
