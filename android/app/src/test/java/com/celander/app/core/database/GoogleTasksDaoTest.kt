package com.celander.app.core.database

import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Task
import com.celander.app.search.FtsQuery
import com.celander.app.task.google.base
import com.celander.app.task.google.fields
import com.celander.app.task.google.withBase
import com.celander.app.testing.TestEnvironment
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which tasks count as changed for Google: the base snapshot decides, whichever path wrote the task. */
@RunWith(RobolectricTestRunner::class)
class GoogleTasksDaoTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private val dao get() = env.db.googleTasksDao()
    private var personal = 0L
    private var list = 0L
    private var taskId = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        personal = env.calendars.first().single().id
        list = env.db.calendarDao().insert(CalendarEntity(name = "My Tasks", colorKey = "teal", createdAt = 0, source = CalendarSource.GOOGLE_TASKS.name))
        dao.upsertList(GoogleTaskListEntity(calendarId = list, account = "me@example.com", remoteId = "L1", isDefault = true))
        taskId = env.tasks.create(Task(calendarId = list, title = "Dentist", note = "bring card", dueDate = LocalDate.of(2026, 10, 30)))
        // Agreed with Google: the link's base equals the task.
        val task = env.db.taskDao().getById(taskId)!!
        dao.upsertLink(
            GoogleTaskLinkEntity(
                taskId = taskId, account = "me@example.com", remoteId = "g1", remoteListId = "L1", etag = "e1",
                baseTitle = "", baseNote = "", baseDueDay = null, baseDone = false, baseCompletedAt = null,
            ).withBase(task.fields()),
        )
    }

    @After
    fun tearDown() = env.close()

    private suspend fun pendingIds() = dao.pendingUpserts().map { it.task.id }

    @Test
    fun agreedTaskIsNotPending() = runBlocking {
        assertTrue(pendingIds().isEmpty())
        assertEquals(0, dao.observePendingCount().first())
    }

    @Test
    fun editorEditIsPending() = runBlocking {
        env.tasks.update(env.tasks.get(taskId)!!.copy(title = "Dentist 9:30"))
        val pending = dao.pendingUpserts().single()
        assertEquals(taskId, pending.task.id)
        assertEquals("L1", pending.listRemoteId)
        assertEquals("me@example.com", pending.listAccount)
        assertEquals("g1", pending.link!!.remoteId)
        assertEquals(1, dao.observePendingCount().first())
    }

    @Test
    fun checkingOffIsPendingFromTheRepositoryAndTheNotification() = runBlocking {
        env.tasks.setDone(taskId, true)
        assertEquals(listOf(taskId), pendingIds())
        env.tasks.setDone(taskId, false)
        assertTrue(pendingIds().isEmpty())
        // The notification's "Done" action writes through TaskDao directly.
        env.db.taskDao().setDone(taskId, true, 1_000, 1_000)
        assertEquals(listOf(taskId), pendingIds())
    }

    @Test
    fun localOnlyFieldsAreNotPending() = runBlocking {
        env.tasks.update(env.tasks.get(taskId)!!.copy(dueTime = LocalTime.of(9, 30), reminders = listOf(10, 60)))
        assertTrue(pendingIds().isEmpty())
    }

    @Test
    fun editThenRevertIsNotPending() = runBlocking {
        val original = env.tasks.get(taskId)!!
        env.tasks.update(original.copy(note = "changed"))
        env.tasks.update(original)
        assertTrue(pendingIds().isEmpty())
    }

    @Test
    fun rejectionParksTheTaskUntilTheNextEdit() = runBlocking {
        env.tasks.update(env.tasks.get(taskId)!!.copy(title = "x".repeat(2000)))
        val updatedAt = env.db.taskDao().getById(taskId)!!.updatedAt
        dao.upsertLink(dao.link(taskId)!!.copy(rejectedUpdatedAt = updatedAt, lastError = "HTTP 400"))
        assertTrue(pendingIds().isEmpty())
        assertEquals(1, dao.rejectedCount())

        env.db.taskDao().update(env.db.taskDao().getById(taskId)!!.copy(title = "short", updatedAt = updatedAt + 1))
        assertEquals(listOf(taskId), pendingIds())
    }

    @Test
    fun newTaskInAGoogleListIsPendingButSamplesAndLocalTasksAreNot() = runBlocking {
        val fresh = env.tasks.create(Task(calendarId = list, title = "New"))
        env.tasks.create(Task(calendarId = list, title = "Sample", isSample = true))
        env.tasks.create(Task(calendarId = personal, title = "Local"))
        val pending = dao.pendingUpserts().single()
        assertEquals(fresh, pending.task.id)
        assertNull(pending.link)
    }

    @Test
    fun deletedTaskBecomesAPendingDeleteWithItsUndoWindow() = runBlocking {
        val removed = env.tasks.delete(taskId)!!
        val tombstone = dao.pendingDeletes().single()
        assertEquals(taskId, tombstone.taskId)
        assertEquals(env.time.now().toEpochMilli(), tombstone.deletedAt)
        assertEquals(1, dao.observePendingCount().first())

        env.tasks.restore(removed)
        assertTrue(dao.pendingDeletes().isEmpty())
        assertNull(dao.link(taskId)!!.deletedAt)
        assertTrue(pendingIds().isEmpty())
    }

    @Test
    fun movingToALocalCalendarIsAPendingDelete() = runBlocking {
        env.tasks.update(env.tasks.get(taskId)!!.copy(calendarId = personal))
        assertEquals(taskId, dao.pendingDeletes().single().taskId)
        assertTrue(pendingIds().isEmpty())
    }

    @Test
    fun deletingAnUnsentTaskLeavesNothingToDelete() = runBlocking {
        val fresh = env.tasks.create(Task(calendarId = list, title = "Never sent"))
        dao.upsertLink(dao.link(taskId)!!.copy(taskId = fresh, remoteId = null, etag = null, insertStartedAt = 1L))
        env.tasks.delete(fresh)
        assertTrue(dao.pendingDeletes().none { it.taskId == fresh })
        dao.dropOrphanedInFlightFor("me@example.com")
        assertNull(dao.link(fresh))
        assertNotNull(dao.link(taskId))
    }

    @Test
    fun applyRemoteKeepsTheIdTheTimeAndSearchHits() = runBlocking {
        env.tasks.update(env.tasks.get(taskId)!!.copy(dueTime = LocalTime.of(8, 0)))
        dao.applyRemote(taskId, "Orthodontist", "bring card", LocalDate.of(2026, 11, 2).toEpochDay(), false, null, list, 5_000)

        val task = env.db.taskDao().getById(taskId)!!
        assertEquals("Orthodontist", task.title)
        assertEquals(8 * 60, task.dueMinute)
        assertEquals(listOf(taskId), env.db.taskDao().search(FtsQuery.build("orthodont")!!).map { it.id })
        assertTrue(env.db.taskDao().search(FtsQuery.build("dentist")!!).isEmpty())

        dao.applyRemote(taskId, "Orthodontist", "", null, false, null, list, 6_000)
        assertNull(env.db.taskDao().getById(taskId)!!.dueMinute)
    }

    @Test
    fun baseSnapshotIgnoresTheCompletionTimeOfOpenTasks() = runBlocking {
        val link = dao.link(taskId)!!.copy(baseDone = false, baseCompletedAt = 99)
        assertNull(link.base().completedAt)
        assertEquals(env.db.taskDao().getById(taskId)!!.fields(), link.base())
    }
}
