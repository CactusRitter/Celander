package com.celander.app.event

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Calendars with their own default reminders: storage and what the editor starts with. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CalendarReminderDefaultsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private var personal = 0L
    private var work = 0L
    private val workDefaults = ReminderDefaults(event = listOf(30), allDay = listOf(1440), task = listOf(60))
    private val day = LocalDate.of(2026, 10, 23).toEpochDay()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        personal = env.calendars.first().single().id
        work = env.calendars.create("Work", "blue")
        env.calendars.setReminderDefaults(work, workDefaults)
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private fun editor() = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, PlaceSearch.None)

    /** Opens [request] once the editor lists Work with its own defaults. */
    private suspend fun open(editor: EditorViewModel, request: EditorRequest): EditorDraft {
        editor.open(request)
        return withTimeout(5_000) {
            editor.state.first { s -> s.draft != null && s.calendars.any { it.id == work && it.reminderDefaults != null } }
        }.draft!!
    }

    private suspend fun EditorViewModel.awaitDraft(check: (EditorDraft) -> Boolean): EditorDraft =
        withTimeout(5_000) { state.first { it.draft?.let(check) == true } }.draft!!

    @Test
    fun ownDefaultsAreStoredAndCanBeRemoved() = runBlocking {
        assertEquals(workDefaults, env.calendars.first().single { it.id == work }.reminderDefaults)
        assertNull(env.calendars.first().single { it.id == personal }.reminderDefaults)
        // No reminders at all is a choice of its own, not "use the defaults".
        val none = ReminderDefaults(emptyList(), emptyList(), emptyList())
        env.calendars.setReminderDefaults(work, none)
        assertEquals(none, env.calendars.first().single { it.id == work }.reminderDefaults)
        env.calendars.setReminderDefaults(work, null)
        assertNull(env.calendars.first().single { it.id == work }.reminderDefaults)
    }

    @Test
    fun renamingKeepsTheOwnDefaults() = runBlocking {
        val calendar = env.calendars.first().single { it.id == work }
        env.calendars.update(calendar.copy(name = "Office", colorKey = "teal"))
        assertEquals(workDefaults, env.calendars.first().single { it.id == work }.reminderDefaults)
    }

    @Test
    fun aNewEventStartsWithItsCalendarsDefaults() = runBlocking {
        env.settings.update { it.copy(defaultCalendarId = work) }
        assertEquals(listOf(30), open(editor(), EditorRequest.NewEvent(day)).reminders)
        assertEquals(listOf(1440), open(editor(), EditorRequest.NewEvent(day, allDay = true)).reminders)
    }

    @Test
    fun aNewTaskStartsWithItsListsTaskDefaults() = runBlocking {
        env.settings.update { it.copy(defaultTaskCalendarId = work) }
        assertEquals(listOf(60), open(editor(), EditorRequest.NewTask(day)).reminders)
    }

    @Test
    fun untouchedRemindersFollowTheCalendar() = runBlocking {
        val editor = editor()
        assertEquals(listOf(10), open(editor, EditorRequest.NewEvent(day)).reminders)
        editor.setCalendar(work)
        assertEquals(listOf(30), editor.awaitDraft { it.calendarId == work }.reminders)
        editor.setCalendar(personal)
        assertEquals(listOf(10), editor.awaitDraft { it.calendarId == personal }.reminders)
    }

    @Test
    fun remindersSetByHandStayWhenTheCalendarChanges() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(day))
        editor.setReminders(listOf(5))
        editor.setCalendar(work)
        val draft = editor.awaitDraft { it.calendarId == work }
        assertEquals(listOf(5), draft.reminders)
        assertFalse(draft.remindersFromDefaults)
    }

    @Test
    fun theAllDaySwitchSwapsTheSelectedCalendarsDefaults() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(day))
        editor.setCalendar(work)
        editor.setAllDay(true)
        assertEquals(listOf(1440), editor.awaitDraft { it.allDay }.reminders)
        editor.setAllDay(false)
        assertEquals(listOf(30), editor.awaitDraft { !it.allDay }.reminders)
    }

    @Test
    fun anExistingEventKeepsItsRemindersWhenMoved() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 23, 14, 0), 60).copy(calendarId = personal, reminders = listOf(10)))
        val editor = editor()
        open(editor, EditorRequest.EditEvent(id, 0))
        editor.setCalendar(work)
        assertEquals(listOf(10), editor.awaitDraft { it.calendarId == work }.reminders)
    }
}
