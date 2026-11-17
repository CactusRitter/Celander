package com.celander.app.calendar.device

import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.recurrence.SeriesScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A delete of a device event that waits for its undo snackbar to expire. */
data class PendingDelete(val eventId: Long, val key: Long, val scope: SeriesScope) {
    fun hides(eventId: Long, key: Long): Boolean = eventId == this.eventId && when (scope) {
        SeriesScope.ALL -> true
        SeriesScope.THIS -> key == this.key
        SeriesScope.THIS_AND_FOLLOWING -> key >= this.key
    }
}

/**
 * Device deletes are held back until the undo snackbar expires: the provider cannot undelete, and
 * inserting the event again would create a new Google event (and may e-mail its guests). Until then
 * the deleted occurrences are hidden everywhere.
 */
@Singleton
class PendingDeviceDeletes @Inject constructor() {
    private val _pending = MutableStateFlow<List<PendingDelete>>(emptyList())
    val pending: StateFlow<List<PendingDelete>> = _pending.asStateFlow()

    fun add(delete: PendingDelete) = _pending.update { it + delete }

    fun remove(delete: PendingDelete) = _pending.update { it - delete }

    fun hidesAll(eventId: Long): Boolean = _pending.value.any { it.eventId == eventId && it.scope == SeriesScope.ALL }
}

fun List<PendingDelete>.hides(occurrence: EventOccurrence): Boolean = any { it.hides(occurrence.event.id, occurrence.originalStart) }
