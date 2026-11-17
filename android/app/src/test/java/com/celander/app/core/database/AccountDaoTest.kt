package com.celander.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.model.AccessRole
import com.celander.app.core.model.AccountType
import com.celander.app.core.model.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tables of the synced accounts (database version 4). */
@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {
    private lateinit var db: CelanderDatabase

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CelanderDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private suspend fun calendar(name: String) = db.calendarDao().insert(
        CalendarEntity(name = name, colorKey = "indigo", createdAt = 1, source = "ACCOUNT"),
    )

    @Test
    fun anAccountIsFoundByItsTypeAndAddress() = runBlocking {
        val id = db.accountDao().insertAccount(AccountEntity(type = AccountType.GOOGLE.name, identifier = "a@example.com", displayName = "A", createdAt = 1))
        assertEquals(id, db.accountDao().account(AccountType.GOOGLE.name, "a@example.com")?.id)
        assertNull(db.accountDao().account(AccountType.MICROSOFT.name, "a@example.com"))
    }

    @Test
    fun removingAnAccountRemovesItsCalendarsEventsAndLinksButNotOthers() = runBlocking {
        val dao = db.accountDao()
        val account = dao.insertAccount(AccountEntity(type = "GOOGLE", identifier = "a@example.com", displayName = "A", createdAt = 1))
        val mine = calendar("Mine")
        val other = calendar("Other")
        dao.upsertRemoteCalendar(RemoteCalendarEntity(mine, account, "primary", AccessRole.OWNER.name, syncToken = "t1"))
        val event = db.eventDao().insert(sampleEvent(mine))
        dao.upsertEventLink(EventLinkEntity(event, mine, "r1", "e1", syncState = SyncState.CLEAN.name))
        val otherEvent = db.eventDao().insert(sampleEvent(other))

        db.withTransaction {
            dao.deleteEventLinksOfAccount(account)
            dao.deleteCalendarsOfAccount(account)
            dao.deleteAccount(account)
        }

        assertEquals(listOf(other), db.calendarDao().getAll().map { it.id })
        assertNull(db.eventDao().getById(event))
        assertEquals(otherEvent, db.eventDao().getById(otherEvent)?.id)
        assertNull(dao.eventLink(event))
        assertEquals(emptyList<RemoteCalendarEntity>(), dao.remoteCalendars(account))
    }

    @Test
    fun onlyEventsWaitingToBeSentArePending() = runBlocking {
        val dao = db.accountDao()
        val account = dao.insertAccount(AccountEntity(type = "GOOGLE", identifier = "a@example.com", displayName = "A", createdAt = 1))
        val cal = calendar("Mine")
        dao.upsertRemoteCalendar(RemoteCalendarEntity(cal, account, "primary", AccessRole.OWNER.name, syncToken = null))
        dao.upsertEventLink(EventLinkEntity(1, cal, "r1", "e", syncState = SyncState.CLEAN.name))
        dao.upsertEventLink(EventLinkEntity(2, cal, null, null, syncState = SyncState.NEW.name))
        dao.upsertEventLink(EventLinkEntity(3, cal, "r3", "e", syncState = SyncState.DELETED.name))
        assertEquals(listOf(2L, 3L), dao.pendingEventLinks(cal).map { it.eventId }.sorted())
    }

    private fun sampleEvent(calendarId: Long) = EventEntity(
        uid = "u$calendarId", calendarId = calendarId, title = "E", location = "", description = "", colorKey = null, allDay = false,
        startUtc = 1_000, endUtc = 2_000, startDay = null, endDay = null, timeZone = "UTC", rrule = null, reminders = emptyList(),
        isSample = false, createdAt = 1, updatedAt = 1,
    )
}
