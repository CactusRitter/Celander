package com.celander.app.calendar.device

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.core.ui.UiText
import com.celander.app.event.EditorOutcome
import com.celander.app.event.EditorViewModel
import com.celander.app.event.EventWithExceptions
import com.celander.app.event.PlaceSearch
import com.celander.app.event.SeriesEditor
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The editor with the phone's calendars: which calendars it offers and what a failed write does. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeviceEditFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private val work = CalendarInfo(SourceIds.device(1), "Work", "blue", source = CalendarSource.DEVICE, accountType = "com.google", maxReminders = 2)
    private val holidays = CalendarInfo(SourceIds.device(2), "Holidays", "green", source = CalendarSource.DEVICE, writable = false)
    private val deviceEvent = timedEvent(SourceIds.device(10), LocalDateTime.of(2026, 10, 23, 14, 0), 60).copy(calendarId = work.id, title = "Planning")
    private val store = FakeStore()

    private inner class FakeSource : DeviceCalendarSource {
        override val status: Flow<DeviceCalendarStatus> = flowOf(DeviceCalendarStatus.ACTIVE)
        override val changes: Flow<Unit> = flowOf(Unit)
        override val calendars: Flow<List<CalendarInfo>> = MutableStateFlow(listOf(work, holidays))
        override val googleAccounts: Flow<List<DeviceAccount>> = flowOf(emptyList())
        override fun occurrences(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Flow<List<EventOccurrence>> = flowOf(emptyList())
        override fun observeEvent(id: Long): Flow<EventWithExceptions?> = calendars.map { if (id == deviceEvent.id) EventWithExceptions(deviceEvent, emptyList()) else null }
        override suspend fun getEvent(id: Long, includePendingDeletes: Boolean) = if (id == deviceEvent.id) EventWithExceptions(deviceEvent, emptyList()) else null
        override suspend fun search(text: String, now: Instant, zone: ZoneId, allowed: (Long) -> Boolean): List<EventOccurrence> = emptyList()
        override suspend fun setVisible(id: Long, visible: Boolean) = Unit
        override suspend fun setColor(id: Long, colorKey: String) = Unit
    }

    private class FakeStore : DeviceEventStore {
        var fail = false
        val created = mutableListOf<Event>()
        val updated = mutableListOf<Event>()

        override suspend fun create(event: Event): Long {
            if (fail) throw DeviceWriteException("Work")
            created += event
            return SourceIds.device(99)
        }

        override suspend fun update(edited: Event) {
            if (fail) throw DeviceWriteException("Work")
            updated += edited
        }

        override suspend fun save(masterId: Long, key: Long, edited: Event, scope: SeriesScope) = Unit
        override suspend fun delete(eventId: Long, key: Long, scope: SeriesScope) = Unit
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnvironment(folder.root, device = FakeSource(), deviceStore = store)
        env.calendars.ensureDefault("Personal")
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private fun editor() = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, PlaceSearch.None)

    private suspend fun open(editor: EditorViewModel, request: EditorRequest) {
        editor.open(request)
        withTimeout(5_000) { editor.state.first { it.draft != null } }
    }

    @Test
    fun chooserOffersOnlyCalendarsThatCanHoldTheEntry() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay()))
        assertEquals(listOf("Personal", "Work"), editor.state.value.calendars.map { it.name })
        editor.setKind(EntryKind.TASK)
        withTimeout(5_000) { while (editor.state.value.draft?.kind != EntryKind.TASK) delay(10) }
        assertEquals(listOf("Personal"), editor.state.value.calendars.map { it.name })
    }

    @Test
    fun aTaskNeverOffersThePhoneCalendarItStartedIn() = runBlocking {
        env.settings.update { it.copy(defaultCalendarId = work.id) }
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay()))
        assertEquals(work.id, editor.state.value.draft!!.calendarId)
        editor.setKind(EntryKind.TASK)
        withTimeout(5_000) { while (editor.state.value.draft?.calendarId == work.id) delay(10) }
        assertEquals(listOf("Personal"), editor.state.value.calendars.map { it.name })
    }

    @Test
    fun deviceCalendarsDropSameDayRemindersAndCapThem() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay()))
        editor.setReminders(listOf(-540, 5, 10, 30))
        editor.setCalendar(work.id)
        assertEquals(listOf(5, 10), editor.state.value.draft!!.reminders)
        assertNotNull(editor.state.value.deviceCalendar)
    }

    @Test
    fun aFailedWriteKeepsTheDraft() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay()))
        editor.setCalendar(work.id)
        editor.setTitle("Dentist")
        store.fail = true
        editor.save()
        withTimeout(5_000) { while (env.snackbars.current.value == null) delay(10) }
        assertEquals("Dentist", editor.state.value.draft?.title)
        assertEquals(UiText.Res(com.celander.app.R.string.event_save_failed, listOf("Work")), env.snackbars.current.value!!.text)

        store.fail = false
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        assertTrue(withTimeout(5_000) { outcome.await() } is EditorOutcome.Saved)
        assertEquals("Dentist", store.created.single().title)
        assertEquals(work.id, store.created.single().calendarId)
    }

    @Test
    fun anExistingDeviceEventStaysInItsCalendar() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.EditEvent(deviceEvent.id, deviceEvent.timing.startInstant(ZoneId.of("UTC")).toEpochMilli()))
        assertEquals(listOf(work.id), editor.state.value.calendars.map { it.id })
        editor.setTitle("Planning (moved)")
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        withTimeout(5_000) { outcome.await() }
        assertEquals("Planning (moved)", store.updated.single().title)
        assertFalse(store.updated.single().id == 0L)
    }
}
