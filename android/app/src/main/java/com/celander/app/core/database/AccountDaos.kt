package com.celander.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun accounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE type = :type AND identifier = :identifier")
    suspend fun account(type: String, identifier: String): AccountEntity?

    @Insert
    suspend fun insertAccount(account: AccountEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    /**
     * Removing an account takes three steps, in one transaction and in this order: [deleteEventLinksOfAccount],
     * [deleteCalendarsOfAccount] (their events go with them) and this. Nothing is sent to the server.
     */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    @Query("DELETE FROM event_links WHERE calendarId IN (SELECT calendarId FROM remote_calendars WHERE accountId = :accountId)")
    suspend fun deleteEventLinksOfAccount(accountId: Long)

    @Query("DELETE FROM calendars WHERE id IN (SELECT calendarId FROM remote_calendars WHERE accountId = :accountId)")
    suspend fun deleteCalendarsOfAccount(accountId: Long)

    @Query("SELECT * FROM remote_calendars")
    fun observeRemoteCalendars(): Flow<List<RemoteCalendarEntity>>

    @Query("SELECT * FROM remote_calendars WHERE accountId = :accountId")
    suspend fun remoteCalendars(accountId: Long): List<RemoteCalendarEntity>

    @Query("SELECT * FROM remote_calendars WHERE calendarId = :calendarId")
    suspend fun remoteCalendar(calendarId: Long): RemoteCalendarEntity?

    @Upsert
    suspend fun upsertRemoteCalendar(calendar: RemoteCalendarEntity)

    @Query("DELETE FROM remote_calendars WHERE calendarId = :calendarId")
    suspend fun deleteRemoteCalendar(calendarId: Long)

    @Query("SELECT * FROM event_links WHERE eventId = :eventId")
    suspend fun eventLink(eventId: Long): EventLinkEntity?

    @Query("SELECT * FROM event_links WHERE calendarId = :calendarId AND remoteId = :remoteId")
    suspend fun eventLinkByRemoteId(calendarId: Long, remoteId: String): EventLinkEntity?

    @Query("SELECT * FROM event_links WHERE calendarId = :calendarId")
    suspend fun eventLinks(calendarId: Long): List<EventLinkEntity>

    /** Events with local changes that have not reached the server (to create, change or delete). */
    @Query("SELECT * FROM event_links WHERE calendarId = :calendarId AND syncState != 'CLEAN'")
    suspend fun pendingEventLinks(calendarId: Long): List<EventLinkEntity>

    @Query("SELECT COUNT(*) FROM event_links WHERE syncState != 'CLEAN'")
    fun observePendingCount(): Flow<Int>

    @Upsert
    suspend fun upsertEventLink(link: EventLinkEntity)

    @Query("DELETE FROM event_links WHERE eventId = :eventId")
    suspend fun deleteEventLink(eventId: Long)

    @Query("SELECT * FROM exception_links WHERE eventId = :eventId")
    suspend fun exceptionLinks(eventId: Long): List<ExceptionLinkEntity>

    @Upsert
    suspend fun upsertExceptionLink(link: ExceptionLinkEntity)

    @Query("DELETE FROM exception_links WHERE eventId = :eventId")
    suspend fun deleteExceptionLinks(eventId: Long)
}
