package com.celander.app.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.model.AccessRole
import com.celander.app.core.model.AccountStatus
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

@RunWith(RobolectricTestRunner::class)
class AccountSyncTest {
    private lateinit var db: CelanderDatabase
    private lateinit var provider: FakeProvider
    private lateinit var sync: AccountSync
    private var accountId = 0L

    private class FakeProvider : CalendarProvider {
        override val type = AccountType.GOOGLE
        override val capabilities = ProviderCapabilities(canWrite = false, maxReminders = null, hasCalendarList = true)
        var calendars = listOf(
            RemoteCalendar("primary", "Mine", "#16a765", AccessRole.OWNER, isPrimary = true, selected = true, defaultReminders = listOf(10), timeZone = "Europe/Berlin"),
        )
        val batches = ArrayDeque<EventSyncResult>()
        var signInFails = false

        override suspend fun listCalendars(account: AccountEntity): List<RemoteCalendar> =
            if (signInFails) throw NeedsSignInException() else calendars

        override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity) = batches.removeFirst()
    }

    @Before
    fun open() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CelanderDatabase::class.java).allowMainThreadQueries().build()
        provider = FakeProvider()
        sync = AccountSync(db, db.accountDao(), db.calendarDao(), db.eventDao(), db.exceptionDao(), mapOf(AccountType.GOOGLE to provider), now = { 1_000L })
        accountId = db.accountDao().insertAccount(AccountEntity(type = "GOOGLE", identifier = "a@example.com", displayName = "A", createdAt = 1))
    }

    @After
    fun close() = db.close()

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private fun single(id: String, title: String, start: String = "2026-11-20T14:00:00Z") = RemoteEvent(
        id, "e-$id", 5,
        ICalEvent(uid = "$id@g", summary = title, start = ms(start), end = ms(start) + 3_600_000, allDay = false, timeZone = "Europe/Berlin"),
    )

    private fun series(id: String) = RemoteEvent(
        id, "e-$id", 5,
        ICalEvent(
            uid = "$id@g", summary = "Gym", start = ms("2026-11-02T17:00:00Z"), end = ms("2026-11-02T18:00:00Z"), allDay = false,
            timeZone = "Europe/Berlin", rrule = "FREQ=WEEKLY;BYDAY=MO", exdates = listOf(ms("2026-11-09T17:00:00Z")),
        ),
    )

    private fun instance(id: String, uid: String, original: String, title: String) = RemoteEvent(
        id, "e-$id", 6,
        ICalEvent(
            uid = uid, summary = title, start = ms(original) + 3_600_000, end = ms(original) + 7_200_000, allDay = false, timeZone = "Europe/Berlin",
            recurrenceId = ms(original), recurrenceAllDay = false, recurrenceTimeZone = "Europe/Berlin",
        ),
    )

    private fun batch(vararg events: RemoteEvent, full: Boolean = false, token: String? = "T") = EventSyncResult(events.toList(), token, full)

    private suspend fun calendarId() = db.accountDao().remoteCalendars(accountId).single().calendarId

    @Test
    fun theFirstSyncCreatesTheCalendarWithItsEventsRemindersAndToken() = runBlocking {
        provider.batches += batch(single("1", "Dentist"), full = true, token = "T1")
        assertEquals(SyncOutcome.Done, sync.sync(accountId))
        val calendar = db.calendarDao().getById(calendarId())!!
        assertEquals("Mine", calendar.name)
        assertEquals("ACCOUNT", calendar.source)
        assertTrue(calendar.visible)
        val event = db.eventDao().getAll().single()
        assertEquals("Dentist", event.title)
        assertEquals(listOf(10), event.reminders)
        assertEquals("T1", db.accountDao().remoteCalendar(calendarId())!!.syncToken)
        assertEquals(AccountStatus.OK.name, db.accountDao().account(accountId)!!.status)
    }

    @Test
    fun aSeriesKeepsItsExdatesAndAChangedOccurrenceArrivingAloneLater() = runBlocking {
        provider.batches += batch(series("s"), full = true)
        sync.sync(accountId)
        val master = db.eventDao().getAll().single()
        assertEquals(1, db.exceptionDao().getForEvent(master.id).size)
        provider.batches += batch(instance("s_1116", "s@g", "2026-11-16T17:00:00Z", "Gym late"))
        sync.sync(accountId)
        val exceptions = db.exceptionDao().getForEvent(master.id)
        assertEquals(2, exceptions.size)
        assertEquals("Gym late", exceptions.single { !it.cancelled }.title)
        val changed = series("s")
        provider.batches += batch(changed.copy(etag = "e2", ical = changed.ical!!.copy(summary = "Gym 2")))
        sync.sync(accountId)
        assertEquals("Gym 2", db.eventDao().getById(master.id)!!.title)
        assertEquals(2, db.exceptionDao().getForEvent(master.id).size)
    }

    @Test
    fun aDeletedEventIsRemovedAndAFullFetchRemovesWhatTheServerNoLongerHas() = runBlocking {
        provider.batches += batch(single("1", "A"), single("2", "B"), full = true)
        sync.sync(accountId)
        provider.batches += batch(RemoteEvent("1", null, 9, ical = null))
        sync.sync(accountId)
        assertEquals(listOf("B"), db.eventDao().getAll().map { it.title })
        provider.batches += batch(single("3", "C"), full = true)
        sync.sync(accountId)
        assertEquals(listOf("C"), db.eventDao().getAll().map { it.title })
        assertNull(db.accountDao().eventLinkByRemoteId(calendarId(), "2"))
    }

    @Test
    fun anEventChangedHereAndNotSentYetIsNotOverwritten() = runBlocking {
        provider.batches += batch(single("1", "Server title"), full = true)
        sync.sync(accountId)
        val id = db.eventDao().getAll().single().id
        db.eventDao().update(db.eventDao().getById(id)!!.copy(title = "My edit"))
        db.accountDao().upsertEventLink(db.accountDao().eventLink(id)!!.copy(syncState = SyncState.DIRTY.name))
        provider.batches += batch(single("1", "Changed at server").copy(etag = "newer"))
        sync.sync(accountId)
        assertEquals("My edit", db.eventDao().getById(id)!!.title)
        assertEquals("newer", db.accountDao().eventLink(id)!!.etag)
    }

    @Test
    fun aCalendarRemovedAtTheServerGoesWithItsEventsAndSignInProblemsAreReported() = runBlocking {
        provider.batches += batch(single("1", "A"), full = true)
        sync.sync(accountId)
        provider.calendars = emptyList()
        sync.sync(accountId)
        assertEquals(0, db.calendarDao().getAll().size)
        assertEquals(0, db.eventDao().getAll().size)
        provider.signInFails = true
        assertEquals(SyncOutcome.NeedsSignIn, sync.sync(accountId))
        assertEquals(AccountStatus.NEEDS_SIGN_IN.name, db.accountDao().account(accountId)!!.status)
    }

    @Test
    fun removingTheAccountLeavesNothingBehind() = runBlocking {
        provider.batches += batch(series("s"), full = true)
        sync.sync(accountId)
        sync.remove(accountId)
        assertNull(db.accountDao().account(accountId))
        assertEquals(0, db.calendarDao().getAll().size)
        assertEquals(0, db.eventDao().getAll().size)
        assertEquals(0, db.exceptionDao().getAll().size)
    }
}
