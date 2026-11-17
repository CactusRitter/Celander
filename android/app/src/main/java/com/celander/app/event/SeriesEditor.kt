package com.celander.app.event

import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.SeriesEdits
import com.celander.app.core.recurrence.SeriesScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves an edited occurrence of a series for the chosen [SeriesScope]. */
@Singleton
class SeriesEditor @Inject constructor(
    private val events: EventRepository,
) {
    /** [edited] is the occurrence originally at [key] after editing (title, timing, rule, …). */
    suspend fun save(masterId: Long, key: Long, edited: Event, scope: SeriesScope) {
        if (SourceIds.isDevice(masterId)) return events.saveDeviceSeries(masterId, key, edited, scope)
        val loaded = events.getWithExceptions(masterId) ?: return
        val master = loaded.event
        when (scope) {
            SeriesScope.THIS -> when (val exception = SeriesEdits.exceptionAt(master, key, edited)) {
                null -> events.deleteException(master.id, key)
                else -> events.saveException(exception)
            }
            SeriesScope.THIS_AND_FOLLOWING -> {
                // Expanding the series to count and check occurrences stays off the main thread.
                val split = withContext(Dispatchers.Default) {
                    SeriesEdits.splitAt(master, key, edited, loaded.exceptions, SeriesEdits.occurrencesBefore(master, key), UUID.randomUUID().toString())
                }
                if (split == null) {
                    saveAll(master, key, edited, loaded.exceptions)
                } else {
                    events.split(split.master, split.masterExceptions, split.series, split.seriesExceptions)
                }
            }
            SeriesScope.ALL -> saveAll(master, key, edited, loaded.exceptions)
        }
    }

    private suspend fun saveAll(master: Event, key: Long, edited: Event, exceptions: List<EventException>) {
        val (updated, kept) = withContext(Dispatchers.Default) { SeriesEdits.applyToAll(master, key, edited, exceptions) }
        events.replace(updated, kept)
    }
}
