package com.celander.app.search

import com.celander.app.core.model.Task
import com.celander.app.search.SearchHit.EventHit
import com.celander.app.search.SearchHit.TaskHit
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private lateinit var search: SearchRepository
    private var personal = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        personal = env.calendars.first().single().id
        search = SearchRepository(env.db, env.db.calendarDao(), env.db.eventDao(), env.db.exceptionDao(), env.db.taskDao(), env.time, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = env.close()

    // "Now" in the test environment is Friday, 23 October 2026, 10:10 in Berlin.
    private suspend fun event(title: String, start: LocalDateTime, rrule: String? = null, calendarId: Long = personal, location: String = "") =
        env.events.create(timedEvent(0, start, 60, rrule).copy(title = title, calendarId = calendarId, location = location))

    @Test
    fun findsPrefixesInTitleAndLocationIgnoringCaseAndAccents() = runBlocking {
        event("Zahnarzt", LocalDateTime.of(2026, 10, 26, 9, 0))
        event("Lunch", LocalDateTime.of(2026, 10, 27, 12, 0), location = "Café Luise")
        assertEquals(listOf("Zahnarzt"), search.search("zahn", SearchFilter()).map { (it as EventHit).occurrence.title })
        assertEquals(listOf("Lunch"), search.search("cafe lu", SearchFilter()).map { (it as EventHit).occurrence.title })
    }

    @Test
    fun hiddenCalendarsAreExcluded() = runBlocking {
        val hidden = env.calendars.create("Hidden", "blue")
        env.calendars.setVisible(hidden, false)
        event("Secret plan", LocalDateTime.of(2026, 10, 26, 9, 0), calendarId = hidden)
        assertTrue(search.search("secret", SearchFilter()).isEmpty())
    }

    @Test
    fun seriesIsListedOnceAtItsNextOccurrence() = runBlocking {
        event("Stand-up", LocalDateTime.of(2026, 10, 5, 8, 0), rrule = "FREQ=DAILY")
        val hit = search.search("stand", SearchFilter()).single() as EventHit
        // Today's 8:00–9:00 is over at 10:10, so the next one is tomorrow.
        assertEquals(LocalDate.of(2026, 10, 24), hit.date)
        assertEquals(false, hit.past)
    }

    @Test
    fun rangeAndCalendarFiltersApply() = runBlocking {
        val work = env.calendars.create("Work", "blue")
        event("Review old", LocalDateTime.of(2026, 10, 1, 9, 0), calendarId = work)
        event("Review new", LocalDateTime.of(2026, 11, 2, 9, 0))
        env.tasks.create(Task(calendarId = personal, title = "Review notes", done = true))

        val titles = { filter: SearchFilter ->
            runBlocking { search.search("review", filter) }.map { hit ->
                when (hit) {
                    is EventHit -> hit.occurrence.title
                    is TaskHit -> hit.item.task.title
                }
            }
        }
        assertEquals(listOf("Review old", "Review new", "Review notes"), titles(SearchFilter()))
        assertEquals(listOf("Review new"), titles(SearchFilter(SearchRange.FUTURE)))
        assertEquals(listOf("Review old", "Review notes"), titles(SearchFilter(SearchRange.PAST)))
        assertEquals(listOf("Review old"), titles(SearchFilter(calendarIds = setOf(work))))
    }
}
