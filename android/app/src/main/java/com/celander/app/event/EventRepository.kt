package com.celander.app.event

import androidx.room.withTransaction
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.EventEntity
import com.celander.app.core.database.EventExceptionEntity
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.toEntity
import com.celander.app.core.database.toModel
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventAccess
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.SeriesCapabilities
import com.celander.app.core.time.TimeSource
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Everything needed to undo the deletion of an event. */
data class EventSnapshot(val event: EventEntity, val exceptions: List<EventExceptionEntity>)

data class EventWithExceptions(
    val event: Event,
    val exceptions: List<EventException>,
    val access: EventAccess = EventAccess(),
    val capabilities: SeriesCapabilities = SeriesCapabilities(),
)

@Singleton
class EventRepository @Inject constructor(
    private val db: CelanderDatabase,
    private val events: EventDao,
    private val exceptions: ExceptionDao,
    private val time: TimeSource,
) {
    suspend fun get(id: Long): Event? = events.getById(id)?.toModel()

    fun observe(id: Long): Flow<EventWithExceptions?> =
        combine(events.observeById(id), exceptions.observeForEvent(id)) { entity, list ->
            entity?.toModel()?.let { event -> EventWithExceptions(event, list.map { it.toModel(event.zone) }) }
        }

    suspend fun getWithExceptions(id: Long): EventWithExceptions? {
        val event = get(id) ?: return null
        return EventWithExceptions(event, exceptions.getForEvent(id).map { it.toModel(event.zone) })
    }

    suspend fun create(event: Event): Long {
        val now = time.now().toEpochMilli()
        return events.insert(event.copy(id = 0, createdAt = now, updatedAt = now).toEntity())
    }

    suspend fun update(event: Event) {
        events.update(event.copy(updatedAt = time.now().toEpochMilli()).toEntity())
    }

    /** Deletes the event (and its exceptions) and returns what is needed to restore it. */
    suspend fun delete(id: Long): EventSnapshot? = db.withTransaction {
        val entity = events.getById(id) ?: return@withTransaction null
        val snapshot = EventSnapshot(entity, exceptions.getForEvent(id))
        events.delete(id)
        snapshot
    }

    suspend fun restore(snapshot: EventSnapshot) = db.withTransaction {
        events.restore(snapshot.event)
        exceptions.restoreAll(snapshot.exceptions)
    }

    /** Replaces an event row and its exceptions in one step (used by series edits and their undo). */
    suspend fun replace(event: Event, newExceptions: List<EventException>) = db.withTransaction {
        events.update(event.copy(updatedAt = time.now().toEpochMilli()).toEntity())
        exceptions.deleteForEvent(event.id)
        exceptions.restoreAll(newExceptions.map { it.copy(id = 0, eventId = event.id).toEntity() })
    }

    /** "This and following": shortens the series and starts the new one in one step. */
    suspend fun split(
        master: Event,
        masterExceptions: List<EventException>,
        series: Event,
        seriesExceptions: List<EventException>,
    ): Long = db.withTransaction {
        replace(master, masterExceptions)
        val id = create(series)
        exceptions.restoreAll(seriesExceptions.map { it.copy(id = 0, eventId = id).toEntity() })
        id
    }

    suspend fun saveException(exception: EventException) = exceptions.upsert(exception.toEntity())

    suspend fun deleteException(eventId: Long, originalStart: Long) = exceptions.delete(eventId, originalStart)
}

val Event.zone: ZoneId
    get() = (timing as? EventTiming.Timed)?.zone ?: ZoneId.systemDefault()
