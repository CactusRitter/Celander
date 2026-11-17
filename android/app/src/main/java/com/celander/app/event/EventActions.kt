package com.celander.app.event

import com.celander.app.core.recurrence.SeriesEdits
import com.celander.app.core.recurrence.SeriesScope
import javax.inject.Inject
import javax.inject.Singleton

/** What a deletion offers: [undo] brings it back. */
data class Undoable(val undo: suspend () -> Unit)

/** Mutations of events that can be undone. */
@Singleton
class EventActions @Inject constructor(
    private val repository: EventRepository,
) {
    /**
     * Deletes [scope] of the series (or the single event) at occurrence [key].
     * Returns how to undo it, or null if nothing was deleted.
     */
    suspend fun delete(eventId: Long, key: Long, scope: SeriesScope): Undoable? {
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
}
