package com.celander.app.intake

import com.celander.app.calendar.data.CalendarContentRepository
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import kotlinx.coroutines.Dispatchers
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

/** Recognising an entry by its UID, and what updating or cancelling does to it. */
@RunWith(RobolectricTestRunner::class)
class IntakeRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private lateinit var intake: IntakeRepository
    private var calendar = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        calendar = env.db.calendarDao().getAll().first().id
        val content = CalendarContentRepository(env.db.calendarDao(), env.db.eventDao(), env.db.exceptionDao(), env.db.taskDao(), Dispatchers.IO)
        intake = IntakeRepository(env.context, env.events, env.db.eventDao(), env.calendars, content)
    }

    @After
    fun tearDown() = env.close()

    private suspend fun judge(entry: IncomingEvent) = IntakePlan.verdict(entry, intake.find(entry))

    private suspend fun all() = env.db.eventDao().getAll()

    private suspend fun add(entry: IncomingEvent) = intake.add(entry, calendar, listOf(10))

    private fun version(sequence: Int, summary: String = "Dinner") =
        entry(ics(vevent(summary = summary, more = arrayOf("SEQUENCE:$sequence")), method = "REQUEST"))

    @Test
    fun theSameUidUpdatesInsteadOfDuplicating() = runBlocking {
        assertEquals(Verdict.NEW, judge(version(1)))
        add(version(1))
        assertEquals(Verdict.UNCHANGED, judge(version(1)))
        val later = version(2, "Dinner at eight")
        assertEquals(Verdict.UPDATE, judge(later))
        intake.apply(later, intake.find(later)!!)
        assertEquals(listOf("Dinner at eight"), all().map { it.title })
        // The reminders chosen when it was added stay.
        assertEquals(listOf(10), all().single().reminders)
        assertEquals(Verdict.OUTDATED, judge(version(1)))
    }

    @Test
    fun anEventTheAccountRenamedIsStillFound() = runBlocking {
        add(version(1))
        val row = all().single()
        env.db.eventDao().update(row.copy(uid = "given-by-the-server"))
        assertEquals(Verdict.UNCHANGED, judge(version(1)))
    }

    @Test
    fun cancellingRemovesOnlyWhatIsThere() = runBlocking {
        val cancel = entry(ics(vevent(), method = "CANCEL"))
        assertEquals(Verdict.NOT_FOUND, judge(cancel))
        add(version(0))
        assertEquals(Verdict.CANCEL, judge(cancel))
        intake.apply(cancel, intake.find(cancel)!!)
        assertEquals(0, all().size)
    }

    @Test
    fun cancellingOneDateKeepsTheSeries() = runBlocking {
        add(entry(ics(vevent(more = arrayOf("RRULE:FREQ=WEEKLY")))))
        val cancel = entry(ics(vevent(start = "20261110T180000Z", end = "20261110T200000Z", more = arrayOf("RECURRENCE-ID:20261110T180000Z")), method = "CANCEL"))
        assertEquals(Verdict.CANCEL, judge(cancel))
        intake.apply(cancel, intake.find(cancel)!!)
        assertEquals(1, all().size)
        assertEquals(listOf(true), env.db.exceptionDao().getForEvent(all().single().id).map { it.cancelled })
        assertEquals(Verdict.UNCHANGED, judge(cancel))
    }

    @Test
    fun anUnknownUidIsNotFound() = runBlocking {
        add(version(0))
        assertNull(intake.find(entry(ics(vevent(uid = "other@example")))))
    }

    @Test
    fun overlappingEventsAreReported() = runBlocking {
        add(version(0))
        val overlapping = entry(ics(vevent(uid = "b", start = "20261103T190000Z", end = "20261103T210000Z")))
        val later = entry(ics(vevent(uid = "c", start = "20261103T200000Z", end = "20261103T210000Z")))
        assertEquals(listOf("Dinner"), intake.conflicts(overlapping.timing, null, BERLIN).map { it.title })
        assertEquals(0, intake.conflicts(later.timing, null, BERLIN).size)
        assertEquals(0, intake.conflicts(overlapping.timing, all().single().id, BERLIN).size)
    }
}
