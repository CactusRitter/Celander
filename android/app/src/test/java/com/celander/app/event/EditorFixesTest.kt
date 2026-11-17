package com.celander.app.event

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.EventException
import com.celander.app.core.model.Task
import com.celander.app.core.recurrence.RRule
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Editor cases from the code review: repetition, end after start, duplicates and outside changes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorFixesTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private var calendarId = 0L

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        calendarId = env.calendars.first().single().id
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private fun editor() = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, PlaceSearch.None)

    private suspend fun open(editor: EditorViewModel, request: EditorRequest): EditorDraft {
        editor.open(request)
        return withTimeout(5_000) { editor.state.first { it.draft != null } }.draft!!
    }

    private suspend fun EditorViewModel.awaitDraft(check: (EditorDraft) -> Boolean): EditorDraft =
        withTimeout(5_000) { state.first { it.draft?.let(check) == true } }.draft!!

    private suspend fun saved(editor: EditorViewModel) = coroutineScope {
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { editor.outcomes.first() }
        editor.save()
        withTimeout(5_000) { outcome.await() }
    }

    @Test
    fun aWeeklyRuleWithAnEndFollowsTheStartToAnotherWeekday() = runBlocking {
        val editor = editor()
        val draft = open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 26).toEpochDay(), 9 * 60))
        editor.setRepeat(RepeatSpec(RepeatKind.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY), end = RepeatEnd.UNTIL, until = LocalDate.of(2026, 12, 31)))
        editor.setStart(draft.startDate.plusDays(1), LocalTime.of(9, 0))

        val rule = RRule.parse(editor.state.value.draft!!.rrule!!)!!
        assertTrue(rule.byDay.isEmpty())
        assertEquals(setOf(DayOfWeek.TUESDAY), editor.state.value.draft!!.repeat.weekdays)
    }

    @Test
    fun movingAnOccurrenceOfASeriesWithAnEndStillOffersThisOnly() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=WEEKLY;UNTIL=20261231T225959Z").copy(calendarId = calendarId))
        val key = LocalDateTime.of(2026, 10, 26, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()
        val editor = editor()
        val draft = open(editor, EditorRequest.EditEvent(id, key))
        editor.setStart(draft.startDate.plusDays(2), LocalTime.of(9, 0))
        editor.save()

        val choices = withTimeout(5_000) { editor.state.first { it.scopeChoices != null } }.scopeChoices!!
        assertTrue(SeriesScope.THIS in choices)
    }

    @Test
    fun aWeeklySeriesOffItsStartDayStartsOnTheFirstChosenDay() = runBlocking {
        val editor = editor()
        // Wednesday 28 October, repeating on Mondays and Fridays.
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 28).toEpochDay(), 10 * 60))
        editor.setTitle("Training")
        editor.setRepeat(RepeatSpec(RepeatKind.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        saved(editor)

        val event = env.db.eventDao().getAll().single()
        assertEquals(LocalDateTime.of(2026, 10, 30, 10, 0).atZone(BERLIN).toInstant().toEpochMilli(), event.startUtc)
    }

    @Test
    fun switchingOffAllDayKeepsTheEndAfterTheStart() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 23).toEpochDay(), 23 * 60, allDay = true))
        editor.setAllDay(false)
        val draft = editor.awaitDraft { !it.allDay }
        assertFalse(draft.endsTooEarly)
    }

    @Test
    fun aTaskWithADueDateBecomesAnEventThatCanBeSaved() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewTask(LocalDate.of(2026, 10, 24).toEpochDay()))
        editor.setDue(LocalDate.of(2026, 10, 24), LocalTime.of(15, 0))
        editor.setKind(EntryKind.EVENT)
        val draft = editor.awaitDraft { it.kind == EntryKind.EVENT }
        assertFalse(draft.endsTooEarly)
        assertTrue(editor.state.value.canSave)
    }

    @Test
    fun aDuplicateOfAChangedOccurrenceKeepsItsPlaceAndNotes() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 19, 9, 0), 60, rrule = "FREQ=DAILY").copy(calendarId = calendarId, title = "Stand-up"))
        val key = LocalDateTime.of(2026, 10, 21, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()
        env.events.saveException(EventException(eventId = id, originalStart = key, location = "Room B", description = "Bring slides"))

        val draft = open(editor(), EditorRequest.DuplicateEvent(id, key))
        assertEquals("Room B", draft.location)
        assertEquals("Bring slides", draft.description)
    }

    @Test
    fun savingATaskKeepsWhatChangedElsewhereMeanwhile() = runBlocking {
        val id = env.tasks.create(Task(calendarId = calendarId, title = "Shopping", note = "A"))
        val editor = editor()
        open(editor, EditorRequest.EditTask(id))
        // A sync brings a new note while the editor is open.
        env.tasks.update(env.tasks.get(id)!!.copy(note = "B"))
        editor.setTitle("Groceries")
        saved(editor)

        val task = env.tasks.get(id)!!
        assertEquals("Groceries", task.title)
        assertEquals("B", task.note)
    }

    @Test
    fun savingAnEventKeepsWhatChangedElsewhereMeanwhile() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 27, 9, 0), 60).copy(calendarId = calendarId, title = "Dentist"))
        val editor = editor()
        open(editor, EditorRequest.EditEvent(id, 0))
        env.events.update(env.events.get(id)!!.copy(location = "Main street 1"))
        editor.setTitle("Dentist (check-up)")
        saved(editor)

        val event = env.events.get(id)!!
        assertEquals("Dentist (check-up)", event.title)
        assertEquals("Main street 1", event.location)
    }

    @Test
    fun theOutcomeWaitsForTheSheetToRead() = runBlocking {
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 27).toEpochDay(), 9 * 60))
        editor.setTitle("Lunch")
        editor.save()
        // Nobody collected while saving (the sheet was being recreated); the result is still there.
        withTimeout(5_000) { while (env.db.eventDao().getAll().isEmpty()) delay(10) }
        assertTrue(withTimeout(5_000) { editor.outcomes.first() } is EditorOutcome.Saved)
    }

    @Test
    fun rulesWithPartsTheEditorCannotShowStayUntouched() {
        val spec = RepeatRules.fromRRule("FREQ=DAILY;BYHOUR=9,17;UNTIL=20261231", LocalDate.of(2026, 10, 1), BERLIN)
        assertEquals(RepeatKind.OTHER, spec.kind)
        val biweekly = RepeatRules.fromRRule("FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,MO;WKST=SU", LocalDate.of(2026, 10, 26), BERLIN)
        assertEquals(DayOfWeek.SUNDAY, biweekly.weekStart)
        assertEquals(DayOfWeek.SUNDAY, RRule.parse(RepeatRules.toRRule(biweekly, LocalDate.of(2026, 10, 27), false, BERLIN)!!)!!.weekStart)
    }
    @Test
    fun aTapInTheRepeatedHourCreatesTheEventInThatHour() = runBlocking {
        // 25 October 2026: in Berlin 2:00 to 3:00 happens twice; the second time is at +01:00.
        val editor = editor()
        open(editor, EditorRequest.NewEvent(LocalDate.of(2026, 10, 25).toEpochDay(), 2 * 60 + 30, startOffsetSeconds = 3600))
        editor.setTitle("Night shift")
        saved(editor)

        val event = env.db.eventDao().getAll().single()
        assertEquals(java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), event.startUtc)
    }
}
