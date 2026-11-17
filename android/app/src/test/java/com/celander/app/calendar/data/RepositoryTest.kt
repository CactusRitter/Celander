package com.celander.app.calendar.data

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.event.EditorOutcome
import com.celander.app.event.EditorViewModel
import com.celander.app.event.PlaceSearch
import com.celander.app.event.SeriesEditor
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnvironment(folder.root)
        runBlocking { env.calendars.ensureDefault("Personal") }
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private suspend fun personalId() = env.calendars.first().single().id

    @Test
    fun firstStartCreatesOneDefaultCalendar() = runBlocking {
        env.calendars.ensureDefault("Again")
        val calendars = env.calendars.first()
        assertEquals(listOf("Personal"), calendars.map { it.name })
        assertEquals(calendars.single().id, env.settings.current().defaultCalendarId)
    }

    @Test
    fun deletedSeriesIsRestoredWithItsExceptions() = runBlocking {
        val id = env.events.create(
            timedEvent(0, LocalDateTime.of(2026, 10, 5, 9, 0), 30, rrule = "FREQ=WEEKLY").copy(calendarId = personalId()),
        )
        env.events.saveException(EventException(eventId = id, originalStart = 123L, cancelled = true))

        val snapshot = env.events.delete(id)!!
        assertNull(env.events.get(id))

        env.events.restore(snapshot)
        val restored = env.events.getWithExceptions(id)!!
        assertEquals("FREQ=WEEKLY", restored.event.rrule)
        assertEquals(listOf(123L), restored.exceptions.map { it.originalStart })
    }

    @Test
    fun sampleDataLoadsAndClearsCompletely() = runBlocking {
        env.samples.load()
        assertTrue(env.samples.hasSampleData.first())
        assertTrue(env.db.eventDao().getAll().isNotEmpty())
        assertEquals(4, env.calendars.first().size)

        env.samples.clear()
        assertFalse(env.samples.hasSampleData.first())
        assertTrue(env.db.eventDao().getAll().isEmpty())
        assertTrue(env.db.taskDao().getAll().isEmpty())
        assertEquals(listOf("Personal"), env.calendars.first().map { it.name })
    }

    private fun editor() = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, PlaceSearch.None)

    @Test
    fun editorUsesSmartDefaultsAndRequiresATitle() = runBlocking {
        val editor = editor()
        editor.open(EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay()))
        val draft = withTimeout(5_000) { editor.state.first { it.draft != null } }.draft!!
        // Now is 10:10, so the event starts at the next half hour and lasts the default hour.
        assertEquals(LocalTime.of(10, 30), draft.startTime)
        assertEquals(LocalTime.of(11, 30), draft.endTime)
        assertEquals(personalId(), draft.calendarId)

        editor.save()
        assertTrue(editor.state.value.titleError)

        editor.setTitle("  Dentist ")
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        val saved = withTimeout(5_000) { outcome.await() } as EditorOutcome.Saved
        assertEquals(EntryKind.EVENT, saved.kind)
        assertTrue(saved.isNew)

        val event = env.db.eventDao().getAll().single()
        assertEquals("Dentist", event.title)
        assertEquals(LocalDateTime.of(2026, 10, 23, 10, 30).atZone(BERLIN).toInstant().toEpochMilli(), event.startUtc)
        assertEquals(personalId(), env.settings.current().lastUsedCalendarId)
    }

    @Test
    fun endBeforeStartIsCorrected() = runBlocking {
        val editor = editor()
        editor.open(EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay(), minuteOfDay = 14 * 60))
        withTimeout(5_000) { editor.state.first { it.draft != null } }
        editor.setEnd(LocalDate.of(2026, 10, 23), LocalTime.of(9, 0))
        val state = withTimeout(5_000) { editor.state.first { it.endAdjusted } }
        assertEquals(LocalTime.of(15, 0), state.draft!!.endTime)
        // The correction lands on the original end, so there is nothing to discard.
        assertFalse(state.dirty)
    }

    @Test
    fun deletedTaskCanBeRestoredFromTheSnackbar() = runBlocking {
        val editor = editor()
        editor.open(EditorRequest.NewTask(LocalDate.of(2026, 10, 24).toEpochDay(), title = "Plants"))
        withTimeout(5_000) { editor.state.first { it.draft != null } }
        var outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        withTimeout(5_000) { outcome.await() }
        val taskId = env.db.taskDao().getAll().single().id

        editor.open(EditorRequest.EditTask(taskId))
        withTimeout(5_000) { editor.state.first { it.draft?.taskId == taskId } }
        outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.deleteTask()
        assertEquals(EditorOutcome.Deleted, withTimeout(5_000) { outcome.await() })
        assertTrue(env.db.taskDao().getAll().isEmpty())

        val message = withTimeout(5_000) { env.snackbars.current.first { it != null } }!!
        env.snackbars.performAction(message)
        val restored = withTimeout(5_000) { env.tasks.visibleTasks.first { it.isNotEmpty() } }.single()
        assertEquals("Plants", restored.title)
        assertEquals(LocalDate.of(2026, 10, 24), restored.dueDate)
    }

    @Test
    fun allDayEventsAreStoredAsDates() = runBlocking {
        val editor = editor()
        editor.open(EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay(), allDay = true, title = "Holiday"))
        withTimeout(5_000) { editor.state.first { it.draft != null } }
        editor.setEnd(LocalDate.of(2026, 10, 25), LocalTime.of(10, 0))
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        withTimeout(5_000) { outcome.await() }
        val event = env.events.get(env.db.eventDao().getAll().single().id)!!
        assertEquals(EventTiming.AllDay(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 25)), event.timing)
    }
}
