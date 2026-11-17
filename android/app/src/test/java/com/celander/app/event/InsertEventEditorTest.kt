package com.celander.app.event

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The editor filled from another app's "Add to calendar" (e.g. a date in an e-mail). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InsertEventEditorTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private suspend fun draftFor(request: EditorRequest): EditorDraft {
        val editor = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, PlaceSearch.None)
        editor.open(request)
        return withTimeout(5_000) { editor.state.first { it.draft != null } }.draft!!
    }

    private fun millis(time: LocalDateTime) = time.atZone(BERLIN).toInstant().toEpochMilli()

    @Test
    fun timesTitlePlaceAndNoteAreTakenOver() = runBlocking {
        val draft = draftFor(
            EditorRequest.InsertEvent(
                beginMillis = millis(LocalDateTime.of(2026, 11, 3, 14, 30)),
                endMillis = millis(LocalDateTime.of(2026, 11, 3, 16, 0)),
                title = "Team-Essen",
                location = "Café Luise",
                description = "Tisch für 6",
                rrule = "FREQ=WEEKLY",
            ),
        )
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 30), draft.start)
        assertEquals(LocalDateTime.of(2026, 11, 3, 16, 0), draft.end)
        assertEquals("Team-Essen", draft.title)
        assertEquals("Café Luise", draft.location)
        assertEquals("Tisch für 6", draft.description)
        assertEquals("FREQ=WEEKLY", draft.rrule)
    }

    @Test
    fun withoutAnEndTheDefaultDurationApplies() = runBlocking {
        val draft = draftFor(EditorRequest.InsertEvent(beginMillis = millis(LocalDateTime.of(2026, 11, 3, 9, 0))))
        val minutes = env.settings.current().defaultDurationMinutes.toLong()
        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0).plusMinutes(minutes), draft.end)
    }

    @Test
    fun allDayEndsAreExclusiveMidnightUtc() = runBlocking {
        // A two-day event: 3 and 4 November, sent with the end at the start of the 5th.
        val begin = LocalDate.of(2026, 11, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val draft = draftFor(EditorRequest.InsertEvent(beginMillis = begin, endMillis = end, allDay = true))
        assertEquals(true, draft.allDay)
        assertEquals(LocalDate.of(2026, 11, 3), draft.startDate)
        assertEquals(LocalDate.of(2026, 11, 4), draft.endDate)
    }

    @Test
    fun anUnreadableRepeatRuleIsDropped() = runBlocking {
        val draft = draftFor(EditorRequest.InsertEvent(beginMillis = millis(LocalDateTime.of(2026, 11, 3, 9, 0)), rrule = "nonsense"))
        assertNull(draft.rrule)
    }
}
