package com.celander.app.event

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.recurrence.SeriesEdits
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SeriesEditFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private var seriesId = 0L
    private val key = LocalDateTime.of(2026, 10, 21, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        val calendar = env.calendars.first().single().id
        seriesId = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=DAILY").copy(calendarId = calendar, title = "Stand-up"))
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private fun editor() = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, PlaceSearch.None)

    private suspend fun openOccurrence(editor: EditorViewModel): EditorDraft {
        editor.open(EditorRequest.EditEvent(seriesId, key))
        return withTimeout(5_000) { editor.state.first { it.draft != null } }.draft!!
    }

    @Test
    fun editorShowsTheTappedOccurrenceAndAsksForTheScope() = runBlocking {
        val editor = editor()
        val draft = openOccurrence(editor)
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0).toLocalDate(), draft.startDate)

        editor.setTitle("Retro")
        editor.save()
        assertEquals(SeriesScope.entries, withTimeout(5_000) { editor.state.first { it.scopeChoices != null } }.scopeChoices)

        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.saveWithScope(SeriesScope.THIS)
        withTimeout(5_000) { outcome.await() }

        val loaded = env.events.getWithExceptions(seriesId)!!
        assertEquals("Stand-up", loaded.event.title)
        assertEquals("Retro", loaded.exceptions.single { it.originalStart == key }.title)
    }

    @Test
    fun seriesWideChangesCannotBeLimitedToOneOccurrence() = runBlocking {
        val editor = editor()
        openOccurrence(editor)
        editor.setReminders(listOf(30))
        editor.save()
        assertEquals(listOf(SeriesScope.THIS_AND_FOLLOWING, SeriesScope.ALL), withTimeout(5_000) { editor.state.first { it.scopeChoices != null } }.scopeChoices)
    }

    @Test
    fun followingSplitsTheSeries() = runBlocking {
        val editor = editor()
        val draft = openOccurrence(editor)
        editor.setStart(draft.startDate, LocalTime.of(11, 0))
        editor.save()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.saveWithScope(SeriesScope.THIS_AND_FOLLOWING)
        withTimeout(5_000) { outcome.await() }

        val all = env.db.eventDao().getAll().sortedBy { it.id }
        assertEquals(2, all.size)
        assertEquals(true, all[0].rrule!!.contains("UNTIL="))
        assertEquals(LocalDateTime.of(2026, 10, 21, 11, 0).atZone(BERLIN).toInstant().toEpochMilli(), all[1].startUtc)
        assertEquals("FREQ=DAILY", all[1].rrule)
    }

    @Test
    fun unchangedSeriesEditClosesWithoutAsking() = runBlocking {
        val editor = editor()
        openOccurrence(editor)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        assertEquals(EditorOutcome.Unchanged, withTimeout(5_000) { outcome.await() })
    }
    private suspend fun saveOccurrence(scope: SeriesScope, edit: (EditorViewModel) -> Unit) = coroutineScope {
        val editor = editor()
        openOccurrence(editor)
        edit(editor)
        editor.save()
        withTimeout(5_000) { editor.state.first { it.scopeChoices != null } }
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.saveWithScope(scope)
        withTimeout(5_000) { outcome.await() }
    }

    @Test
    fun aSecondChangeOfTheSameOccurrenceIsKept() = runBlocking {
        saveOccurrence(SeriesScope.THIS) { it.setTitle("Retro") }
        saveOccurrence(SeriesScope.THIS) { it.setLocation("Room B") }

        val exception = env.events.getWithExceptions(seriesId)!!.exceptions.single { it.originalStart == key }
        assertEquals("Retro", exception.title)
        assertEquals("Room B", exception.location)
        assertEquals(null, exception.timing)
    }

    @Test
    fun aChangedOccurrenceCanBeCancelled() = runBlocking {
        saveOccurrence(SeriesScope.THIS) { it.setTitle("Retro") }
        env.events.saveException(SeriesEdits.cancel(seriesId, key))

        assertEquals(true, env.events.getWithExceptions(seriesId)!!.exceptions.single { it.originalStart == key }.cancelled)
    }

    @Test
    fun renamingAllFromAMovedOccurrenceKeepsTheSeriesTime() = runBlocking {
        saveOccurrence(SeriesScope.THIS) { editor -> editor.setStart(editor.state.value.draft!!.startDate, LocalTime.of(11, 0)) }
        saveOccurrence(SeriesScope.ALL) { it.setTitle("Daily") }

        val loaded = env.events.getWithExceptions(seriesId)!!
        assertEquals("Daily", loaded.event.title)
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0).atZone(BERLIN).toInstant(), loaded.event.timing.startInstant(BERLIN))
        val moved = loaded.exceptions.single { it.originalStart == key }
        assertEquals(LocalDateTime.of(2026, 10, 21, 11, 0).atZone(BERLIN).toInstant(), moved.timing!!.startInstant(BERLIN))
        assertEquals(null, moved.title)
    }
    @Test
    fun aRenameElsewhereWhileTheEditorIsOpenSurvivesAnEditOfAll() = runBlocking {
        val editor = editor()
        openOccurrence(editor)
        env.events.update(env.events.get(seriesId)!!.copy(title = "Team sync"))
        editor.setLocation("Room B")
        editor.save()
        withTimeout(5_000) { editor.state.first { it.scopeChoices != null } }
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.saveWithScope(SeriesScope.ALL)
        withTimeout(5_000) { outcome.await() }

        val series = env.events.get(seriesId)!!
        assertEquals("Team sync", series.title)
        assertEquals("Room B", series.location)
    }

    @Test
    fun aNewRuleFromAChangedOccurrenceLeavesNoStrayEvent() = runBlocking {
        saveOccurrence(SeriesScope.THIS) { it.setLocation("Aula") }
        saveOccurrence(SeriesScope.THIS_AND_FOLLOWING) { it.setRepeat(RepeatSpec(RepeatKind.WEEKLY, weekdays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY))) }

        val series = env.db.eventDao().getAll().maxBy { it.id }
        assertEquals(LocalDateTime.of(2026, 10, 23, 9, 0).atZone(BERLIN).toInstant().toEpochMilli(), series.startUtc)
        assertEquals(emptyList<Long>(), env.events.getWithExceptions(series.id)!!.exceptions.map { it.originalStart })
    }
}
