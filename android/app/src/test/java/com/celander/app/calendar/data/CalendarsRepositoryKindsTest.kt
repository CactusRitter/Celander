package com.celander.app.calendar.data

import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.testing.TestEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Events never land in a Google list; Google lists never count as the user's own calendars. */
@RunWith(RobolectricTestRunner::class)
class CalendarsRepositoryKindsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private var personal = 0L
    private var googleList = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        personal = env.calendars.first().single().id
        googleList = env.db.calendarDao().insert(CalendarEntity(name = "My Tasks", colorKey = "teal", createdAt = 0, source = CalendarSource.GOOGLE_TASKS.name))
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun preferredCalendarDependsOnTheKind() = runBlocking {
        env.settings.update { it.copy(lastUsedCalendarId = googleList, lastUsedTaskCalendarId = googleList) }
        assertEquals(personal, env.calendars.preferredId(EntryKind.EVENT))
        assertEquals(googleList, env.calendars.preferredId(EntryKind.TASK))
    }

    @Test
    fun taskDefaultFallsBackToTheEventDefault() = runBlocking {
        assertEquals(personal, env.calendars.preferredId(EntryKind.TASK))
        env.calendars.setDefault(googleList)
        assertEquals(googleList, env.settings.current().defaultTaskCalendarId)
        assertEquals(personal, env.settings.current().defaultCalendarId)
        assertEquals(googleList, env.calendars.preferredId(EntryKind.TASK))
    }

    @Test
    fun onlyLocalCalendarsCount() = runBlocking {
        env.calendars.ensureDefault("Again")
        assertEquals(1, env.calendars.calendarCount())
        // The last local calendar stays, and Google lists cannot be deleted in Celander.
        assertNull(env.calendars.delete(personal))
        assertNull(env.calendars.delete(googleList))
    }

    @Test
    fun googleListsKeepTheirName() = runBlocking {
        val list = env.calendars.first().first { it.id == googleList }
        env.calendars.update(list.copy(name = "Renamed", colorKey = "coral"))
        val updated = env.calendars.first().first { it.id == googleList }
        assertEquals("My Tasks", updated.name)
        assertEquals("coral", updated.colorKey)
    }

    @Test
    fun hiddenCalendarsAreNotOffered() = runBlocking {
        val work = env.calendars.create("Work", "blue")
        env.settings.update { it.copy(lastUsedCalendarId = work, defaultTaskCalendarId = googleList) }
        env.calendars.setVisible(work, false)
        env.calendars.setVisible(googleList, false)
        assertEquals(personal, env.calendars.preferredId(EntryKind.EVENT))
        assertEquals(personal, env.calendars.preferredId(EntryKind.TASK))
    }
}
