package com.celander.app.task.google

import android.content.Context
import com.celander.app.core.database.TaskEntity
import com.celander.app.core.model.Task
import com.celander.app.task.TaskRepository
import com.celander.app.testing.TestEnvironment
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Google refusing single tasks, and errors that pass or concern the whole run. */
@RunWith(RobolectricTestRunner::class)
class GoogleTasksRefusalsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private val clock = MutableTimeSource(Instant.parse("2026-10-23T08:00:00Z").toEpochMilli())
    private val server = FakeTasksRemote(clock)
    private val auth = FakeAuthorizer()
    private lateinit var account: GoogleTasksAccounts
    private lateinit var sync: GoogleTasksSync
    private lateinit var tasks: TaskRepository
    private val dao get() = env.db.googleTasksDao()
    private val taskDao get() = env.db.taskDao()

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        server.addList("L1", "My Tasks", default = true)
        account = GoogleTasksAccounts(env.context.getSharedPreferences("google_tasks_refusals_test", Context.MODE_PRIVATE))
        account.connect(EMAIL)
        sync = GoogleTasksSync(env.db, account, auth, server, env.settings, clock)
        tasks = TaskRepository(env.db, taskDao, dao, clock)
    }

    @After
    fun tearDown() = env.close()

    private suspend fun calendarOf(listRemoteId: String): Long = dao.lists().first { it.remoteId == listRemoteId }.calendarId

    private suspend fun local(remoteId: String): TaskEntity? = dao.linkByRemoteId(remoteId)?.let { taskDao.getById(it.taskId) }

    @Test
    fun anUploadGoogleRefusesIsParkedAndTheOthersGoThrough() = runBlocking {
        sync.run()
        val refused = tasks.create(Task(calendarId = calendarOf("L1"), title = "Not allowed"))
        clock.advance(1_000)
        tasks.create(Task(calendarId = calendarOf("L1"), title = "Fine"))
        var inserts = 0
        server.fault = { if (it == "insert" && inserts++ == 0) TasksApiException(403, listOf("forbidden")) else null }

        assertTrue(sync.run() is SyncOutcome.Done)

        assertEquals(listOf("Fine"), server.live("L1").map { it.title })
        assertNotNull(dao.link(refused)!!.rejectedUpdatedAt)
        assertEquals(1, account[EMAIL]!!.rejectedCount)
        assertTrue(dao.pendingUpserts().isEmpty())
    }

    @Test
    fun errorsThatConcernEveryTaskStillEndTheRun() = runBlocking {
        sync.run()
        tasks.create(Task(calendarId = calendarOf("L1"), title = "Any"))

        server.fault = { if (it == "insert") TasksApiException(403, listOf("insufficientPermissions")) else null }
        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.NeedsReauth), sync.run())
        server.fault = { if (it == "insert") TasksApiException(403, listOf("dailyLimitExceeded")) else null }
        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Failed(403)), sync.run())

        assertEquals(1, dao.pendingUpserts().size)
        assertEquals(0, dao.rejectedCount())
    }

    @Test
    fun passingErrorsAreRetriedInsteadOfParked() = runBlocking {
        sync.run()
        val id = tasks.create(Task(calendarId = calendarOf("L1"), title = "First"))
        sync.run()
        val remoteId = dao.link(id)!!.remoteId!!
        clock.advance(1_000)
        tasks.update(tasks.get(id)!!.copy(title = "Changed"))
        val passing = listOf(
            TasksApiException(408),
            TasksApiException(409),
            TasksApiException(429),
            TasksApiException(503, listOf("backendError")),
            TasksApiException(403, listOf("userRateLimitExceeded")),
            TasksApiException(403, listOf("dailyLimitExceeded")),
            TasksApiException(403, listOf("backendError")),
        )

        for (error in passing) {
            server.fault = { if (it == "patch") error else null }
            assertEquals(error.message, SyncOutcome.Retry(GoogleTasksStatus.Failed(error.code)), sync.run())
            assertEquals(error.message, 0, dao.rejectedCount())
            assertEquals(error.message, 1, dao.pendingUpserts().size)
        }

        server.fault = { null }
        assertTrue(sync.run() is SyncOutcome.Done)
        assertEquals("Changed", server.task(remoteId)!!.title)
    }

    @Test
    fun refusalsWithoutASureSignEndTheRunAndLeaveTheTaskPending() = runBlocking {
        sync.run()
        tasks.create(Task(calendarId = calendarOf("L1"), title = "Any"))
        // A 403 without a readable reason (a proxy's page) or with one that does not name this task.
        val unclear = listOf(TasksApiException(403), TasksApiException(403, listOf("limitExceeded")), TasksApiException(412))

        for (error in unclear) {
            server.fault = { if (it == "insert") error else null }
            assertEquals(error.message, SyncOutcome.Stopped(GoogleTasksStatus.Failed(error.code)), sync.run())
            assertEquals(error.message, 0, dao.rejectedCount())
            assertEquals(error.message, 1, dao.pendingUpserts().size)
        }

        server.fault = { null }
        assertTrue(sync.run() is SyncOutcome.Done)
        assertEquals(listOf("Any"), server.live("L1").map { it.title })
    }

    @Test
    fun aDeleteThatFailsForAWhileIsRetried() = runBlocking {
        val remoteId = server.serverAdd("L1", "Gone soon")
        sync.run()
        tasks.delete(local(remoteId)!!.id)
        clock.advance(GoogleTasksSync.DELETE_GRACE + 1_000)
        server.fault = { if (it == "delete") TasksApiException(409) else null }

        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Failed(409)), sync.run())
        assertEquals(1, dao.pendingDeletes().size)

        server.fault = { null }
        assertTrue(sync.run() is SyncOutcome.Done)
        assertTrue(server.live("L1").isEmpty())
    }

    @Test
    fun aDeleteGoogleRefusesDoesNotHoldUpTheOthers() = runBlocking {
        val first = server.serverAdd("L1", "Shared")
        val second = server.serverAdd("L1", "Mine")
        sync.run()
        tasks.delete(local(first)!!.id)
        tasks.delete(local(second)!!.id)
        clock.advance(GoogleTasksSync.DELETE_GRACE + 1_000)
        var deletes = 0
        server.fault = { if (it == "delete" && deletes++ == 0) TasksApiException(403, listOf("forbidden")) else null }

        assertTrue(sync.run() is SyncOutcome.Done)

        // The refused delete was tried once in this run, the other one went through.
        assertEquals(2, server.calls.count { it.startsWith("DELETE") })
        assertEquals(1, server.live("L1").size)
        assertNotNull(dao.pendingDeletes().single().lastError)

        server.fault = { null }
        assertTrue(sync.run() is SyncOutcome.Done)
        assertTrue(server.live("L1").isEmpty())
        assertTrue(dao.pendingDeletes().isEmpty())
        assertTrue(taskDao.getAll().isEmpty())
    }

    private companion object {
        const val EMAIL = "me@example.com"
    }
}
