package com.celander.app.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.AccountTriggers
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventEntity
import com.celander.app.core.database.EventExceptionEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.model.AccessRole
import com.celander.app.core.model.AccountType
import com.celander.app.core.model.SyncState
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Changes made here reach the server: the triggers mark them, the sync sends them. */
@RunWith(RobolectricTestRunner::class)
class AccountPushTest {
    private lateinit var db: CelanderDatabase
    private lateinit var provider: RecordingProvider
    private lateinit var sync: AccountSync
    private var accountId = 0L
    private var calendarId = 0L

    private class RecordingProvider : CalendarProvider {
        override val type = AccountType.GOOGLE
        override val capabilities = ProviderCapabilities(canWrite = true, maxReminders = null, hasCalendarList = true)
        var role = AccessRole.OWNER
        val log = mutableListOf<String>()
        val created = mutableListOf<ICalEvent>()
        val updated = mutableListOf<ICalEvent>()
        val occurrences = mutableListOf<ICalEvent>()
        var conflictOnce = false
        var counter = 0

        override suspend fun listCalendars(account: AccountEntity) =
            listOf(RemoteCalendar("primary", "Mine", null, role, isPrimary = true, selected = true, defaultReminders = emptyList(), timeZone = "UTC"))

        override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity) = EventSyncResult(emptyList(), "T", full = false)

        override suspend fun create(account: AccountEntity, calendar: RemoteCalendarEntity, master: ICalEvent, reminders: List<Int>): RemoteWrite {
            log += "create"
            created += master
            return RemoteWrite("r${++counter}", "uid-r$counter@g", "etag-$counter", 10)
        }

        override suspend fun update(account: AccountEntity, calendar: RemoteCalendarEntity, remoteId: String, master: ICalEvent, reminders: List<Int>, etag: String?): RemoteWrite {
            log += "update $remoteId etag=$etag"
            if (conflictOnce && etag != null) {
                conflictOnce = false
                throw ConflictException()
            }
            updated += master
            return RemoteWrite(remoteId, master.uid, "etag-u${++counter}", 11)
        }

        override suspend fun writeOccurrence(account: AccountEntity, calendar: RemoteCalendarEntity, masterRemoteId: String, occurrence: ICalEvent, reminders: List<Int>): RemoteWrite {
            log += "occurrence $masterRemoteId"
            occurrences += occurrence
            return RemoteWrite(masterRemoteId + "_x", occurrence.uid, "etag-o", 12)
        }

        override suspend fun delete(account: AccountEntity, calendar: RemoteCalendarEntity, remoteId: String, etag: String?) {
            log += "delete $remoteId"
        }
    }

    @Before
    fun open() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CelanderDatabase::class.java).allowMainThreadQueries().addCallback(AccountTriggers.callback).build()
        provider = RecordingProvider()
        sync = AccountSync(db, db.accountDao(), db.calendarDao(), db.eventDao(), db.exceptionDao(), mapOf(AccountType.GOOGLE to provider), now = { 1_000L })
        accountId = db.accountDao().insertAccount(AccountEntity(type = "GOOGLE", identifier = "a@example.com", displayName = "A", createdAt = 1))
        sync.sync(accountId)
        calendarId = db.accountDao().remoteCalendars(accountId).single().calendarId
    }

    @After
    fun close() = db.close()

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private fun entity(title: String, rrule: String? = null) = EventEntity(
        uid = "local-uid", calendarId = calendarId, title = title, location = "", description = "", colorKey = null, allDay = false,
        startUtc = ms("2026-11-20T10:00:00Z"), endUtc = ms("2026-11-20T11:00:00Z"), startDay = null, endDay = null, timeZone = "UTC",
        rrule = rrule, reminders = listOf(10), isSample = false, createdAt = 1, updatedAt = 1,
    )

    private suspend fun link(id: Long) = db.accountDao().eventLink(id)

    @Test
    fun aNewEventIsCreatedAtTheServerAndKeepsItsAnswer() = runBlocking {
        val id = db.eventDao().insert(entity("Dentist"))
        assertEquals(SyncState.NEW.name, link(id)!!.syncState)
        sync.sync(accountId)
        assertEquals(listOf("create"), provider.log)
        assertEquals("Dentist", provider.created.single().summary)
        val l = link(id)!!
        assertEquals(SyncState.CLEAN.name, l.syncState)
        assertEquals("r1", l.remoteId)
        assertEquals("etag-1", l.etag)
        assertEquals("uid-r1@g", db.eventDao().getById(id)!!.uid)
        // Nothing left to send: the next sync sends nothing.
        sync.sync(accountId)
        assertEquals(listOf("create"), provider.log)
    }

    @Test
    fun aChangedEventIsUpdatedWithItsEtagAndAConflictIsOverwritten() = runBlocking {
        val id = db.eventDao().insert(entity("Dentist"))
        sync.sync(accountId)
        db.eventDao().update(db.eventDao().getById(id)!!.copy(title = "Dentist 2"))
        assertEquals(SyncState.DIRTY.name, link(id)!!.syncState)
        provider.conflictOnce = true
        sync.sync(accountId)
        assertEquals(listOf("create", "update r1 etag=etag-1", "update r1 etag=null"), provider.log)
        assertEquals("Dentist 2", provider.updated.single().summary)
        assertEquals(SyncState.CLEAN.name, link(id)!!.syncState)
    }

    @Test
    fun aDeletedEventIsDeletedAtTheServerAndOneThatNeverArrivedJustVanishes() = runBlocking {
        val sent = db.eventDao().insert(entity("Sent"))
        sync.sync(accountId)
        val unsent = db.eventDao().insert(entity("Never sent"))
        db.eventDao().delete(sent)
        db.eventDao().delete(unsent)
        assertEquals(SyncState.DELETED.name, link(sent)!!.syncState)
        assertNull(link(unsent))
        sync.sync(accountId)
        assertEquals(listOf("create", "delete r1"), provider.log)
        assertNull(link(sent))
    }

    @Test
    fun aChangedOccurrenceOfASeriesIsSentAfterTheSeries() = runBlocking {
        val id = db.eventDao().insert(entity("Gym", rrule = "FREQ=WEEKLY"))
        sync.sync(accountId)
        db.exceptionDao().upsert(
            EventExceptionEntity(
                eventId = id, originalStart = ms("2026-11-27T10:00:00Z"), cancelled = false, title = "Gym late", location = null,
                description = null, allDay = false, startUtc = ms("2026-11-27T12:00:00Z"), endUtc = ms("2026-11-27T13:00:00Z"), startDay = null, endDay = null,
            ),
        )
        assertEquals(SyncState.DIRTY.name, link(id)!!.syncState)
        sync.sync(accountId)
        assertEquals(listOf("create", "update r1 etag=etag-1", "occurrence r1"), provider.log)
        assertEquals("Gym late", provider.occurrences.single().summary)
        assertEquals(ms("2026-11-27T10:00:00Z"), provider.occurrences.single().recurrenceId)
        assertEquals(1, db.accountDao().exceptionLinks(id).size)
    }

    @Test
    fun aCancelledOccurrenceTravelsAsAnExdateOfTheSeries() = runBlocking {
        val id = db.eventDao().insert(entity("Gym", rrule = "FREQ=WEEKLY"))
        sync.sync(accountId)
        db.exceptionDao().upsert(
            EventExceptionEntity(eventId = id, originalStart = ms("2026-11-27T10:00:00Z"), cancelled = true, title = null, location = null, description = null, allDay = null, startUtc = null, endUtc = null, startDay = null, endDay = null),
        )
        sync.sync(accountId)
        assertEquals(listOf(ms("2026-11-27T10:00:00Z")), provider.updated.single().exdates)
    }

    @Test
    fun readOnlyCalendarsAreNeverWrittenTo() = runBlocking {
        provider.role = AccessRole.READER
        sync.sync(accountId)
        val id = db.eventDao().insert(entity("Holiday"))
        sync.sync(accountId)
        assertTrue(provider.log.isEmpty())
        assertEquals(SyncState.NEW.name, link(id)!!.syncState)
    }

    @Test
    fun changesThatCameFromTheServerAreNotSentBack() = runBlocking {
        val remote = RemoteEvent("s1", "e1", 5, ICalEvent(uid = "s1@g", summary = "Gym", start = ms("2026-11-02T17:00:00Z"), end = ms("2026-11-02T18:00:00Z"), allDay = false, timeZone = "UTC", rrule = "FREQ=WEEKLY"))
        val occurrence = RemoteEvent(
            "s1_20261109T170000Z", "e2", 6,
            ICalEvent(
                uid = "s1@g", summary = "Gym late", start = ms("2026-11-09T18:00:00Z"), end = ms("2026-11-09T19:00:00Z"), allDay = false, timeZone = "UTC",
                recurrenceId = ms("2026-11-09T17:00:00Z"), recurrenceAllDay = false, recurrenceTimeZone = "UTC",
            ),
        )
        val fresh = object : CalendarProvider by provider {
            val batches = ArrayDeque(listOf(EventSyncResult(listOf(remote), "T1", full = true), EventSyncResult(listOf(occurrence), "T2", full = false)))
            override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity) = batches.removeFirst()
        }
        val s = AccountSync(db, db.accountDao(), db.calendarDao(), db.eventDao(), db.exceptionDao(), mapOf(AccountType.GOOGLE to fresh), now = { 1_000L })
        s.sync(accountId)
        s.sync(accountId)
        assertEquals(0, db.accountDao().pendingEventLinks(calendarId).size)
        assertTrue(provider.log.isEmpty())
    }
}
