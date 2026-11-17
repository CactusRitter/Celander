package com.celander.app.event

import com.celander.app.calendar.device.PendingDelete
import com.celander.app.calendar.device.PendingDeviceDeletes
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.SeriesEdits
import com.celander.app.core.recurrence.SeriesScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a deletion offers: [undo] brings it back; [commit], if set, makes it final and runs once the
 * undo chance is over (the phone's calendars cannot bring deleted events back).
 */
data class Undoable(val undo: suspend () -> Unit, val commit: (suspend () -> Unit)? = null)

/** Mutations of events that can be undone. */
@Singleton
class EventActions @Inject constructor(
    private val repository: EventRepository,
    private val pendingDeletes: PendingDeviceDeletes,
) {
    /**
     * Deletes [scope] of the series (or the single event) at occurrence [key].
     * Returns how to undo it, or null if nothing was deleted.
     */
    suspend fun delete(eventId: Long, key: Long, scope: SeriesScope): Undoable? {
        if (SourceIds.isDevice(eventId)) return deleteDevice(eventId, key, scope)
        val before = repository.getWithExceptions(eventId) ?: return null
        val event = before.event
        if (!event.isRecurring || scope == SeriesScope.ALL) {
            val snapshot = repository.delete(eventId) ?: return null
            return Undoable({ repository.restore(snapshot) })
        }
        when (scope) {
            SeriesScope.THIS -> repository.saveException(SeriesEdits.cancel(eventId, key))
            SeriesScope.THIS_AND_FOLLOWING -> {
                val shortened = SeriesEdits.endBefore(event, key)
                if (shortened == null) {
                    val snapshot = repository.delete(eventId) ?: return null
                    return Undoable({ repository.restore(snapshot) })
                }
                repository.replace(shortened, SeriesEdits.exceptionsBefore(before.exceptions, key))
            }
            SeriesScope.ALL -> Unit
        }
        return Undoable({ repository.replace(event, before.exceptions) })
    }

    /** Hidden at once, deleted in the provider only once undo is no longer possible. */
    private suspend fun deleteDevice(eventId: Long, key: Long, scope: SeriesScope): Undoable? {
        val event = repository.get(eventId) ?: return null
        val effective = if (event.isRecurring) scope else SeriesScope.ALL
        val pending = PendingDelete(eventId, key, effective)
        pendingDeletes.add(pending)
        return Undoable(
            undo = { pendingDeletes.remove(pending) },
            commit = {
                try {
                    repository.deleteDevice(eventId, key, effective)
                } finally {
                    pendingDeletes.remove(pending)
                }
            },
        )
    }
}
