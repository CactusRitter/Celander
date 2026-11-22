package com.celander.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Row of [CalendarDao.observeEntryCounts]. */
data class CalendarEntryCount(val calendarId: Long, val entries: Int)

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendars ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars ORDER BY sortOrder, id")
    suspend fun getAll(): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun getById(id: Long): CalendarEntity?

    @Query("SELECT COUNT(*) FROM calendars")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM calendars WHERE source = 'LOCAL'")
    suspend fun countLocal(): Int

    @Query("SELECT * FROM calendars WHERE source = :source ORDER BY sortOrder, id")
    suspend fun getBySource(source: String): List<CalendarEntity>

    /** Removes the calendars of a source; their tasks go with them (no push: links are deleted first). */
    @Query("DELETE FROM calendars WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM calendars")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(calendar: CalendarEntity): Long

    @Update
    suspend fun update(calendar: CalendarEntity)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE calendars SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE calendars SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT (SELECT COUNT(*) FROM events WHERE calendarId = :id) + (SELECT COUNT(*) FROM tasks WHERE calendarId = :id)")
    suspend fun entryCount(id: Long): Int

    /** Entries (events and tasks) per calendar, for the calendar list in the settings. */
    @Query(
        "SELECT c.id AS calendarId, (SELECT COUNT(*) FROM events WHERE calendarId = c.id) + " +
            "(SELECT COUNT(*) FROM tasks WHERE calendarId = c.id) AS entries FROM calendars c",
    )
    fun observeEntryCounts(): Flow<List<CalendarEntryCount>>

    /** Removes everything; events, exceptions and tasks go with their calendars. */
    @Query("DELETE FROM calendars")
    suspend fun deleteAll()

    @Query("DELETE FROM calendars WHERE isSample = 1")
    suspend fun deleteSamples()

    /** Sample calendars plus sample entries, which may have been moved to the user's own calendars. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM calendars WHERE isSample = 1) + (SELECT COUNT(*) FROM events WHERE isSample = 1) + " +
            "(SELECT COUNT(*) FROM tasks WHERE isSample = 1)",
    )
    fun observeSampleCount(): Flow<Int>
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE calendarId = :calendarId")
    suspend fun getForCalendar(calendarId: Long): List<EventEntity>

    /**
     * Events that may produce occurrences in a window: single events overlapping it, series starting
     * before its end, and series with an exception moved into it.
     */
    @Query(
        """
        SELECT e.* FROM events e INNER JOIN calendars c ON c.id = e.calendarId
        WHERE (c.visible = 1 AND c.enabled = 1 OR :includeHidden) AND (
            (e.rrule IS NOT NULL AND ((e.allDay = 0 AND e.startUtc < :endUtc) OR (e.allDay = 1 AND e.startDay <= :lastDay)))
            OR (e.rrule IS NULL AND e.allDay = 0 AND e.startUtc < :endUtc AND e.endUtc >= :startUtc)
            OR (e.rrule IS NULL AND e.allDay = 1 AND e.startDay <= :lastDay AND e.endDay >= :firstDay)
            OR e.id IN (
                SELECT x.eventId FROM event_exceptions x WHERE x.cancelled = 0 AND (
                    (x.startUtc IS NOT NULL AND x.startUtc < :endUtc AND x.endUtc >= :startUtc)
                    OR (x.startDay IS NOT NULL AND x.startDay <= :lastDay AND x.endDay >= :firstDay)
                )
            )
        )
        """,
    )
    fun observeCandidates(startUtc: Long, endUtc: Long, firstDay: Long, lastDay: Long, includeHidden: Boolean): Flow<List<EventEntity>>

    @Query(
        """
        SELECT e.* FROM events e INNER JOIN calendars c ON c.id = e.calendarId
        WHERE (c.visible = 1 AND c.enabled = 1 OR :includeHidden) AND (
            (e.rrule IS NOT NULL AND ((e.allDay = 0 AND e.startUtc < :endUtc) OR (e.allDay = 1 AND e.startDay <= :lastDay)))
            OR (e.rrule IS NULL AND e.allDay = 0 AND e.startUtc < :endUtc AND e.endUtc >= :startUtc)
            OR (e.rrule IS NULL AND e.allDay = 1 AND e.startDay <= :lastDay AND e.endDay >= :firstDay)
            OR e.id IN (
                SELECT x.eventId FROM event_exceptions x WHERE x.cancelled = 0 AND (
                    (x.startUtc IS NOT NULL AND x.startUtc < :endUtc AND x.endUtc >= :startUtc)
                    OR (x.startDay IS NOT NULL AND x.startDay <= :lastDay AND x.endDay >= :firstDay)
                )
            )
        )
        """,
    )
    suspend fun getCandidates(startUtc: Long, endUtc: Long, firstDay: Long, lastDay: Long, includeHidden: Boolean): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    fun observeById(id: Long): Flow<EventEntity?>

    @Query("SELECT * FROM events ORDER BY id")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    fun observeCount(): Flow<Int>

    /** Full-text search; [match] is an FTS4 query such as `"zahn"*`. Hidden calendars are excluded. */
    @Query(
        """
        SELECT e.* FROM events e
        INNER JOIN events_fts f ON f.rowid = e.id
        INNER JOIN calendars c ON c.id = e.calendarId
        WHERE events_fts MATCH :match AND c.visible = 1 AND c.enabled = 1
        """,
    )
    suspend fun search(match: String): List<EventEntity>

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(event: EventEntity)

    @Update
    suspend fun update(event: EventEntity)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM events WHERE isSample = 1")
    suspend fun deleteSamples()

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<EventEntity>
}

@Dao
interface ExceptionDao {
    @Query("SELECT * FROM event_exceptions WHERE eventId IN (:eventIds)")
    suspend fun getForEvents(eventIds: List<Long>): List<EventExceptionEntity>

    @Query("SELECT * FROM event_exceptions")
    fun observeAll(): Flow<List<EventExceptionEntity>>

    @Query("SELECT * FROM event_exceptions")
    suspend fun getAll(): List<EventExceptionEntity>

    @Query("SELECT * FROM event_exceptions WHERE eventId = :eventId")
    suspend fun getForEvent(eventId: Long): List<EventExceptionEntity>

    @Query("SELECT * FROM event_exceptions WHERE eventId = :eventId")
    fun observeForEvent(eventId: Long): Flow<List<EventExceptionEntity>>

    /** Replaces an earlier exception of the same occurrence (the key is unique, the id is not known). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exception: EventExceptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(exceptions: List<EventExceptionEntity>)

    @Query("DELETE FROM event_exceptions WHERE eventId = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    @Query("DELETE FROM event_exceptions WHERE eventId = :eventId AND originalStart = :originalStart")
    suspend fun delete(eventId: Long, originalStart: Long)

    @Query("DELETE FROM event_exceptions WHERE eventId = :eventId AND originalStart >= :from")
    suspend fun deleteFrom(eventId: Long, from: Long)

    @Query("DELETE FROM event_exceptions WHERE eventId = :eventId AND cancelled = 0")
    suspend fun deleteOverrides(eventId: Long)

    /**
     * Changed occurrences of series in visible calendars with a title, place or description of their
     * own. These texts are not in the FTS index, so the search matches them in memory.
     */
    @Query(
        """
        SELECT x.* FROM event_exceptions x
        INNER JOIN events e ON e.id = x.eventId
        INNER JOIN calendars c ON c.id = e.calendarId
        WHERE c.visible = 1 AND c.enabled = 1 AND e.rrule IS NOT NULL AND x.cancelled = 0
            AND (x.title IS NOT NULL OR x.location IS NOT NULL OR x.description IS NOT NULL)
        """,
    )
    suspend fun getWithOwnText(): List<EventExceptionEntity>
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE calendarId = :calendarId")
    suspend fun getForCalendar(calendarId: Long): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(tasks: List<TaskEntity>)

    @Query(
        """
        SELECT t.* FROM tasks t INNER JOIN calendars c ON c.id = t.calendarId
        WHERE t.done = 0 AND t.dueDay BETWEEN :firstDay AND :lastDay AND (c.visible = 1 AND c.enabled = 1 OR :includeHidden)
        """,
    )
    fun observeOpenDue(firstDay: Long, lastDay: Long, includeHidden: Boolean): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT t.* FROM tasks t INNER JOIN calendars c ON c.id = t.calendarId
        WHERE t.done = 0 AND t.dueDay BETWEEN :firstDay AND :lastDay AND (c.visible = 1 AND c.enabled = 1 OR :includeHidden)
        """,
    )
    suspend fun getOpenDue(firstDay: Long, lastDay: Long, includeHidden: Boolean): List<TaskEntity>

    @Query("SELECT t.* FROM tasks t INNER JOIN calendars c ON c.id = t.calendarId WHERE c.visible = 1 AND c.enabled = 1")
    fun observeVisible(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<TaskEntity?>

    @Query("SELECT COUNT(*) FROM tasks")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT t.* FROM tasks t
        INNER JOIN tasks_fts f ON f.rowid = t.id
        INNER JOIN calendars c ON c.id = t.calendarId
        WHERE tasks_fts MATCH :match AND c.visible = 1 AND c.enabled = 1
        """,
    )
    suspend fun search(match: String): List<TaskEntity>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET done = :done, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, completedAt: Long?, updatedAt: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tasks WHERE isSample = 1")
    suspend fun deleteSamples()
}

@Dao
interface SnoozeDao {
    @Query("DELETE FROM snoozes")
    suspend fun deleteAll()

    @Query("SELECT * FROM snoozes")
    suspend fun getAll(): List<SnoozeEntity>

    @Upsert
    suspend fun upsert(snooze: SnoozeEntity)

    @Query("DELETE FROM snoozes WHERE `key` = :key")
    suspend fun delete(key: String)
}
