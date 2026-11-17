package com.celander.app.search

import com.celander.app.core.database.EventExceptionEntity
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.search.SearchHit.EventHit
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executors

/** Changed occurrences of local series: found by their own texts, and skipped when renamed away. */
@RunWith(RobolectricTestRunner::class)
class SearchExceptionsTest {
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
    private suspend fun dailyStandUp(): Long =
        env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 5, 8, 0), 60, "FREQ=DAILY").copy(title = "Stand-up", calendarId = personal))

    private suspend fun rename(eventId: Long, day: LocalDate, title: String? = null, location: String? = null) {
        val key = day.atTime(8, 0).atZone(BERLIN).toInstant().toEpochMilli()
        env.events.saveException(EventException(eventId = eventId, originalStart = key, title = title, location = location))
    }

    @Test
    fun changedOccurrenceIsFoundByItsOwnTexts() = runBlocking {
        val id = dailyStandUp()
        rename(id, LocalDate.of(2026, 10, 28), title = "Retro")
        rename(id, LocalDate.of(2026, 10, 30), location = "Café Luise")

        val retro = search.search("retro", SearchFilter()).single() as EventHit
        assertEquals("Retro", retro.occurrence.title)
        assertEquals(LocalDate.of(2026, 10, 28), retro.date)
        assertTrue(retro.occurrence.isException)

        val cafe = search.search("cafe", SearchFilter()).single() as EventHit
        assertEquals(LocalDate.of(2026, 10, 30), cafe.date)
    }

    @Test
    fun seriesHitSkipsAnOccurrenceRenamedAway() = runBlocking {
        val id = dailyStandUp()
        // Today's 8:00 is over, so tomorrow's would be next, but it has another title.
        rename(id, LocalDate.of(2026, 10, 24), title = "Retro")

        val hit = search.search("stand", SearchFilter()).single() as EventHit
        assertEquals("Stand-up", hit.occurrence.title)
        assertEquals(LocalDate.of(2026, 10, 25), hit.date)
    }

    @Test
    fun matchingChangedOccurrencesRunsOnTheSearchDispatcher() = runBlocking {
        val id = dailyStandUp()
        rename(id, LocalDate.of(2026, 10, 28), title = "Retro")
        // Where the code after each read goes on: the changed occurrences' texts are matched right
        // after they are read.
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val exceptions = object : ExceptionDao by env.db.exceptionDao() {
            override suspend fun getWithOwnText(): List<EventExceptionEntity> =
                env.db.exceptionDao().getWithOwnText().also { threads += "exceptions@" + Thread.currentThread().name.substringBefore(" @") }
        }
        val worker = Executors.newSingleThreadExecutor { Thread(it, WORKER) }.asCoroutineDispatcher()
        try {
            val onWorker = SearchRepository(env.db, env.db.calendarDao(), env.db.eventDao(), exceptions, env.db.taskDao(), env.time, worker)

            // runBlocking's thread stands in for the main thread that collects the results.
            val hit = onWorker.search("retro", SearchFilter()).single() as EventHit

            assertEquals("Retro", hit.occurrence.title)
            assertEquals(listOf("exceptions@$WORKER"), threads.toList())
        } finally {
            worker.close()
        }
    }

    private companion object {
        const val WORKER = "search-worker"
    }
}
