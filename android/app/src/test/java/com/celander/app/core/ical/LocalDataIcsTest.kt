package com.celander.app.core.ical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventEntity
import com.celander.app.core.database.EventExceptionEntity
import com.celander.app.core.database.TaskEntity
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalDataIcsTest {
    private lateinit var db: CelanderDatabase
    private lateinit var ics: LocalDataIcs

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CelanderDatabase::class.java).allowMainThreadQueries().build()
        ics = LocalDataIcs(db, db.calendarDao(), db.eventDao(), db.exceptionDao(), db.taskDao())
    }

    @After
    fun close() = db.close()

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private suspend fun fill(): Pair<Long, Long> {
        val work = db.calendarDao().insert(CalendarEntity(name = "Work", colorKey = "blue", createdAt = 1))
        val home = db.calendarDao().insert(CalendarEntity(name = "Home", colorKey = "green", sortOrder = 1, createdAt = 1))
        val gym = db.eventDao().insert(
            EventEntity(
                uid = "gym@local", calendarId = work, title = "Gym", location = "Hall", description = "d", colorKey = null, allDay = false,
                startUtc = ms("2026-11-20T10:00:00Z"), endUtc = ms("2026-11-20T11:00:00Z"), startDay = null, endDay = null, timeZone = "UTC",
                rrule = "FREQ=WEEKLY", reminders = listOf(10), isSample = false, createdAt = 1, updatedAt = 1,
            ),
        )
        db.exceptionDao().upsert(
            EventExceptionEntity(eventId = gym, originalStart = ms("2026-11-27T10:00:00Z"), cancelled = false, title = "Gym late", location = null, description = null, allDay = false, startUtc = ms("2026-11-27T12:00:00Z"), endUtc = ms("2026-11-27T13:00:00Z"), startDay = null, endDay = null),
        )
        db.exceptionDao().upsert(
            EventExceptionEntity(eventId = gym, originalStart = ms("2026-12-04T10:00:00Z"), cancelled = true, title = null, location = null, description = null, allDay = null, startUtc = null, endUtc = null, startDay = null, endDay = null),
        )
        db.taskDao().insert(
            TaskEntity(calendarId = home, title = "Pay rent", note = "n", dueDay = 20_000, dueMinute = 9 * 60, reminders = listOf(30), done = true, completedAt = 5, isSample = false, createdAt = 2, updatedAt = 2),
        )
        // Sample data never travels.
        db.eventDao().insert(
            EventEntity(
                uid = "sample@local", calendarId = home, title = "Sample", location = "", description = "", colorKey = null, allDay = true,
                startUtc = null, endUtc = null, startDay = 20_000, endDay = 20_001, timeZone = "UTC", rrule = null, reminders = emptyList(), isSample = true, createdAt = 1, updatedAt = 1,
            ),
        )
        return work to home
    }

    private suspend fun wipe() {
        db.calendarDao().deleteAll()
    }

    @Test
    fun theFileCarriesEventsSeriesChangesAndTasksWithTheirCalendars() = runBlocking {
        fill()
        val text = ics.export()
        assertTrue(text.contains("BEGIN:VTODO"))
        assertTrue(text.contains("X-CELANDER-CALENDAR:Work"))
        assertTrue(text.contains("X-CELANDER-CALENDAR:Home"))
        assertFalse(text.contains("Sample"))
        val parsed = IcsParser.parseCalendar(text)
        // The series, its changed occurrence; the cancelled one is an EXDATE of the series.
        assertEquals(2, parsed.events.size)
        assertEquals(1, parsed.todos.size)
    }

    @Test
    fun importingPutsEverythingBackInItsCalendarAndNeverTwice() = runBlocking {
        fill()
        val text = ics.export()
        wipe()
        val result = ics.import(text)
        assertEquals(1, result.events)
        assertEquals(1, result.tasks)
        val calendars = db.calendarDao().getAll().associateBy { it.name }
        assertEquals(setOf("Work", "Home"), calendars.keys)
        val event = db.eventDao().getForCalendar(calendars.getValue("Work").id).single()
        assertEquals("Gym", event.title)
        assertEquals("FREQ=WEEKLY", event.rrule)
        val changes = db.exceptionDao().getForEvent(event.id).associateBy { it.originalStart }
        assertEquals("Gym late", changes.getValue(ms("2026-11-27T10:00:00Z")).title)
        assertTrue(changes.getValue(ms("2026-12-04T10:00:00Z")).cancelled)
        val task = db.taskDao().getForCalendar(calendars.getValue("Home").id).single()
        assertEquals("Pay rent", task.title)
        assertTrue(task.done)
        assertEquals(listOf(30), task.reminders)
        assertEquals(20_000L, task.dueDay)
        // The same file again adds nothing.
        val again = ics.import(text)
        assertEquals(0, again.events)
        assertEquals(0, again.tasks)
        assertEquals(2, again.skipped)
    }

    @Test
    fun aFileFromAnotherAppGoesToTheFirstCalendar() = runBlocking {
        val first = db.calendarDao().insert(CalendarEntity(name = "Mine", colorKey = "blue", createdAt = 1))
        val result = ics.import(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x1\r\nDTSTART:20261120T100000Z\r\nDTEND:20261120T110000Z\r\nSUMMARY:Outside\r\nEND:VEVENT\r\nBEGIN:VTODO\r\nUID:x2\r\nSUMMARY:Chore\r\nEND:VTODO\r\nEND:VCALENDAR\r\n",
        )
        assertEquals(1, result.events)
        assertEquals(1, result.tasks)
        assertEquals("Outside", db.eventDao().getForCalendar(first).single().title)
        assertEquals("Chore", db.taskDao().getForCalendar(first).single().title)
    }
}
