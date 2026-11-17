package com.celander.app.calendar.device

import com.celander.app.core.model.Event
import com.celander.app.core.recurrence.SeriesScope

/** A write to the phone's calendars failed; [calendarName] names the calendar for the message. */
class DeviceWriteException(val calendarName: String, cause: Throwable? = null) : Exception("Write to $calendarName failed", cause)

/** Writes events of the phone's calendars. All ids are Celander device ids; failures throw [DeviceWriteException]. */
interface DeviceEventStore {
    /** Inserts [event] into its calendar and returns the new event's id. */
    suspend fun create(event: Event): Long

    /** The whole event becomes [edited] (a single event, or a series edited as a whole). */
    suspend fun update(edited: Event)

    /** [edited] is the occurrence [key] of series [masterId] after editing, saved for [scope]. */
    suspend fun save(masterId: Long, key: Long, edited: Event, scope: SeriesScope)

    /** Deletes [scope] of the event at occurrence [key]; not undoable, so callers delay it. */
    suspend fun delete(eventId: Long, key: Long, scope: SeriesScope)

    /** For tests and builds without device calendars: every write fails. */
    object None : DeviceEventStore {
        override suspend fun create(event: Event): Long = throw DeviceWriteException("")
        override suspend fun update(edited: Event) = throw DeviceWriteException("")
        override suspend fun save(masterId: Long, key: Long, edited: Event, scope: SeriesScope) = throw DeviceWriteException("")
        override suspend fun delete(eventId: Long, key: Long, scope: SeriesScope) = throw DeviceWriteException("")
    }
}
