package com.celander.app.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DevicePrefsDao {
    @Query("SELECT * FROM device_calendar_prefs")
    fun observeAll(): Flow<List<DeviceCalendarPrefEntity>>

    @Query("SELECT * FROM device_calendar_prefs WHERE stableKey = :key")
    suspend fun get(key: String): DeviceCalendarPrefEntity?

    @Upsert
    suspend fun upsert(pref: DeviceCalendarPrefEntity)
}

/** A task that differs from what Google last confirmed, with its list and (if any) its link. */
data class PendingUpsert(
    @Embedded val task: TaskEntity,
    val listRemoteId: String,
    val listAccount: String,
    @Embedded(prefix = "link_") val link: GoogleTaskLinkEntity?,
)

/** Local changes of one Google account that have not reached Google yet. */
data class PendingCount(val account: String, val count: Int)

@Dao
interface GoogleTasksDao {
    @Query("SELECT * FROM google_task_lists")
    suspend fun lists(): List<GoogleTaskListEntity>

    @Query("SELECT * FROM google_task_lists")
    fun observeLists(): Flow<List<GoogleTaskListEntity>>

    @Query("SELECT * FROM google_task_lists WHERE account = :account AND remoteId = :remoteId")
    suspend fun listByRemoteId(account: String, remoteId: String): GoogleTaskListEntity?

    @Upsert
    suspend fun upsertList(list: GoogleTaskListEntity)

    @Query("DELETE FROM google_task_lists WHERE calendarId = :calendarId")
    suspend fun deleteList(calendarId: Long)

    @Query("SELECT * FROM google_task_links WHERE remoteId = :remoteId")
    suspend fun linkByRemoteId(remoteId: String): GoogleTaskLinkEntity?

    @Query("SELECT * FROM google_task_links WHERE taskId = :taskId")
    suspend fun link(taskId: Long): GoogleTaskLinkEntity?

    @Query("SELECT * FROM google_task_links WHERE remoteListId = :listRemoteId AND remoteId IS NOT NULL")
    suspend fun linksForList(listRemoteId: String): List<GoogleTaskLinkEntity>

    @Query("SELECT * FROM google_task_links WHERE remoteListId = :listRemoteId AND remoteId IS NULL AND insertStartedAt IS NOT NULL")
    suspend fun inFlightLinks(listRemoteId: String): List<GoogleTaskLinkEntity>

    @Upsert
    suspend fun upsertLink(link: GoogleTaskLinkEntity)

    @Query("DELETE FROM google_task_links WHERE taskId = :taskId")
    suspend fun deleteLink(taskId: Long)

    @Query("DELETE FROM google_task_links")
    suspend fun deleteAllLinks()

    @Query("DELETE FROM google_task_links WHERE account = :account")
    suspend fun deleteLinksForAccount(account: String)

    @Query("DELETE FROM google_task_links WHERE taskId IN (SELECT id FROM tasks WHERE calendarId = :calendarId)")
    suspend fun deleteLinksForCalendar(calendarId: Long)

    /** Also removes tombstones of tasks deleted in Celander, which no longer sit in the list's calendar. */
    @Query("DELETE FROM google_task_links WHERE remoteListId = :listRemoteId")
    suspend fun deleteLinksForList(listRemoteId: String)

    /** Marks the start of the undo window for a deleted task; the delete is sent once it passed. */
    @Query("UPDATE google_task_links SET deletedAt = :at WHERE taskId = :taskId")
    suspend fun markDeleted(taskId: Long, at: Long)

    @Query("UPDATE google_task_links SET deletedAt = NULL WHERE taskId = :taskId")
    suspend fun clearDeleted(taskId: Long)

    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    /**
     * Writes pulled fields in place (never REPLACE: that would skip the FTS delete trigger). The local
     * due time survives as long as there is a due date.
     */
    @Query(
        """
        UPDATE tasks SET title = :title, note = :note, dueDay = :dueDay,
            dueMinute = CASE WHEN :dueDay IS NULL THEN NULL ELSE dueMinute END,
            done = :done, completedAt = :completedAt, calendarId = :calendarId, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun applyRemote(id: Long, title: String, note: String, dueDay: Long?, done: Boolean, completedAt: Long?, calendarId: Long, updatedAt: Long)

    @Query("UPDATE tasks SET calendarId = :calendarId WHERE id = :taskId")
    suspend fun moveTask(taskId: Long, calendarId: Long)

    /**
     * Tasks in Google lists that differ from what Google last confirmed, oldest change first. A task
     * moved in from another account's list waits until that account deleted it (see [pendingDeletes]).
     */
    @Query(
        """
        SELECT t.*, g.remoteId AS listRemoteId, g.account AS listAccount,
            l.taskId AS link_taskId, l.account AS link_account, l.remoteId AS link_remoteId,
            l.remoteListId AS link_remoteListId, l.etag AS link_etag, l.remoteUpdated AS link_remoteUpdated,
            l.baseTitle AS link_baseTitle, l.baseNote AS link_baseNote, l.baseDueDay AS link_baseDueDay,
            l.baseDone AS link_baseDone, l.baseCompletedAt AS link_baseCompletedAt,
            l.insertStartedAt AS link_insertStartedAt, l.deletedAt AS link_deletedAt,
            l.rejectedUpdatedAt AS link_rejectedUpdatedAt, l.lastError AS link_lastError
        FROM tasks t
        JOIN google_task_lists g ON g.calendarId = t.calendarId
        LEFT JOIN google_task_links l ON l.taskId = t.id
        WHERE t.isSample = 0 AND (l.taskId IS NULL OR l.account = g.account)
            AND (l.rejectedUpdatedAt IS NULL OR l.rejectedUpdatedAt != t.updatedAt)
            AND (l.taskId IS NULL OR l.remoteId IS NULL OR l.remoteListId != g.remoteId
                OR t.title != l.baseTitle OR t.note != l.baseNote OR t.dueDay IS NOT l.baseDueDay
                OR t.done != l.baseDone OR (t.done = 1 AND t.completedAt IS NOT l.baseCompletedAt))
        ORDER BY t.updatedAt
        """,
    )
    suspend fun pendingUpserts(): List<PendingUpsert>

    /** [pendingUpserts] for the lists of [account]. */
    @Query(
        """
        SELECT t.*, g.remoteId AS listRemoteId, g.account AS listAccount,
            l.taskId AS link_taskId, l.account AS link_account, l.remoteId AS link_remoteId,
            l.remoteListId AS link_remoteListId, l.etag AS link_etag, l.remoteUpdated AS link_remoteUpdated,
            l.baseTitle AS link_baseTitle, l.baseNote AS link_baseNote, l.baseDueDay AS link_baseDueDay,
            l.baseDone AS link_baseDone, l.baseCompletedAt AS link_baseCompletedAt,
            l.insertStartedAt AS link_insertStartedAt, l.deletedAt AS link_deletedAt,
            l.rejectedUpdatedAt AS link_rejectedUpdatedAt, l.lastError AS link_lastError
        FROM tasks t
        JOIN google_task_lists g ON g.calendarId = t.calendarId
        LEFT JOIN google_task_links l ON l.taskId = t.id
        WHERE g.account = :account AND t.isSample = 0 AND (l.taskId IS NULL OR l.account = g.account)
            AND (l.rejectedUpdatedAt IS NULL OR l.rejectedUpdatedAt != t.updatedAt)
            AND (l.taskId IS NULL OR l.remoteId IS NULL OR l.remoteListId != g.remoteId
                OR t.title != l.baseTitle OR t.note != l.baseNote OR t.dueDay IS NOT l.baseDueDay
                OR t.done != l.baseDone OR (t.done = 1 AND t.completedAt IS NOT l.baseCompletedAt))
        ORDER BY t.updatedAt
        """,
    )
    suspend fun pendingUpsertsFor(account: String): List<PendingUpsert>

    /**
     * Links whose task is gone, no longer in a Google list or now in another account's list: Google
     * still has the task in the link's account.
     */
    @Query(
        """
        SELECT l.* FROM google_task_links l
        LEFT JOIN tasks t ON t.id = l.taskId
        LEFT JOIN google_task_lists g ON g.calendarId = t.calendarId
        WHERE l.remoteId IS NOT NULL AND (t.id IS NULL OR g.calendarId IS NULL OR g.account != l.account)
        """,
    )
    suspend fun pendingDeletes(): List<GoogleTaskLinkEntity>

    /** [pendingDeletes] of [account]. */
    @Query(
        """
        SELECT l.* FROM google_task_links l
        LEFT JOIN tasks t ON t.id = l.taskId
        LEFT JOIN google_task_lists g ON g.calendarId = t.calendarId
        WHERE l.account = :account AND l.remoteId IS NOT NULL AND (t.id IS NULL OR g.calendarId IS NULL OR g.account != l.account)
        """,
    )
    suspend fun pendingDeletesFor(account: String): List<GoogleTaskLinkEntity>

    /**
     * Links of [account] whose first upload never got an answer while their task is gone or no longer
     * in one of the account's lists. Called after the pulls, which adopt uploads that did arrive.
     */
    @Query(
        """
        DELETE FROM google_task_links WHERE account = :account AND remoteId IS NULL AND taskId NOT IN (
            SELECT t.id FROM tasks t JOIN google_task_lists g ON g.calendarId = t.calendarId WHERE g.account = :account
        )
        """,
    )
    suspend fun dropOrphanedInFlightFor(account: String)

    @Query("SELECT COUNT(*) FROM google_task_links WHERE rejectedUpdatedAt IS NOT NULL")
    suspend fun rejectedCount(): Int

    @Query("SELECT COUNT(*) FROM google_task_links WHERE account = :account AND rejectedUpdatedAt IS NOT NULL")
    suspend fun rejectedCountFor(account: String): Int

    /** Local changes not yet at Google (uploads plus deletes), per account; accounts without any are missing. */
    @Query(
        """
        SELECT account, COUNT(*) AS count FROM (
            SELECT g.account AS account FROM tasks t
            JOIN google_task_lists g ON g.calendarId = t.calendarId
            LEFT JOIN google_task_links l ON l.taskId = t.id
            WHERE t.isSample = 0 AND (l.taskId IS NULL OR l.account = g.account)
                AND (l.rejectedUpdatedAt IS NULL OR l.rejectedUpdatedAt != t.updatedAt)
                AND (l.taskId IS NULL OR l.remoteId IS NULL OR l.remoteListId != g.remoteId
                    OR t.title != l.baseTitle OR t.note != l.baseNote OR t.dueDay IS NOT l.baseDueDay
                    OR t.done != l.baseDone OR (t.done = 1 AND t.completedAt IS NOT l.baseCompletedAt))
            UNION ALL
            SELECT l.account AS account FROM google_task_links l
            LEFT JOIN tasks t ON t.id = l.taskId
            LEFT JOIN google_task_lists g ON g.calendarId = t.calendarId
            WHERE l.remoteId IS NOT NULL AND (t.id IS NULL OR g.calendarId IS NULL OR g.account != l.account)
        ) GROUP BY account
        """,
    )
    fun observePendingCounts(): Flow<List<PendingCount>>

    /** Local changes not yet at Google, all accounts together. */
    @Query(
        """
        SELECT (
            SELECT COUNT(*) FROM tasks t
            JOIN google_task_lists g ON g.calendarId = t.calendarId
            LEFT JOIN google_task_links l ON l.taskId = t.id
            WHERE t.isSample = 0 AND (l.taskId IS NULL OR l.account = g.account)
                AND (l.rejectedUpdatedAt IS NULL OR l.rejectedUpdatedAt != t.updatedAt)
                AND (l.taskId IS NULL OR l.remoteId IS NULL OR l.remoteListId != g.remoteId
                    OR t.title != l.baseTitle OR t.note != l.baseNote OR t.dueDay IS NOT l.baseDueDay
                    OR t.done != l.baseDone OR (t.done = 1 AND t.completedAt IS NOT l.baseCompletedAt))
        ) + (
            SELECT COUNT(*) FROM google_task_links l
            LEFT JOIN tasks t ON t.id = l.taskId
            LEFT JOIN google_task_lists g ON g.calendarId = t.calendarId
            WHERE l.remoteId IS NOT NULL AND (t.id IS NULL OR g.calendarId IS NULL OR g.account != l.account)
        )
        """,
    )
    fun observePendingCount(): Flow<Int>
}
