package com.celander.app.task.google

import android.content.Context
import com.celander.app.core.database.TaskEntity
import com.celander.app.core.googletasks.GoogleTaskMapper
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Task
import com.celander.app.settings.DataReset
import com.celander.app.task.TaskRepository
import com.celander.app.testing.TestEnvironment
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleTasksSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private val clock = MutableTimeSource(Instant.parse("2026-10-23T08:00:00Z").toEpochMilli())
    private val server = FakeTasksRemote(clock)
    private val auth = FakeAuthorizer()
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var account: GoogleTasksAccounts
    private lateinit var sync: GoogleTasksSync
    private lateinit var tasks: TaskRepository
    private lateinit var connection: GoogleTasksConnection
    private var personal = 0L
    private val dao get() = env.db.googleTasksDao()
    private val taskDao get() = env.db.taskDao()
    private val state get() = account[EMAIL]!!

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        personal = env.calendars.first().single().id
        server.addList("L1", "My Tasks", default = true)
        server.addList("L2", "Work")
        account = GoogleTasksAccounts(env.context.getSharedPreferences("google_tasks_test", Context.MODE_PRIVATE))
        account.connect(EMAIL)
        sync = GoogleTasksSync(env.db, account, auth, server, env.settings, clock)
        tasks = TaskRepository(env.db, taskDao, dao, clock)
        connection = GoogleTasksConnection(env.db, account, sync, scheduler, env.settings, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private suspend fun calendarOf(listRemoteId: String): Long = dao.lists().first { it.remoteId == listRemoteId }.calendarId

    private suspend fun local(remoteId: String): TaskEntity? = dao.linkByRemoteId(remoteId)?.let { taskDao.getById(it.taskId) }

    private suspend fun edit(id: Long, transform: (Task) -> Task) {
        clock.advance(1_000)
        tasks.update(transform(tasks.get(id)!!))
    }

    private fun daysAgo(days: Long) = GoogleTaskMapper.formatTime(clock.millis - days * 86_400_000L)

    @Test
    fun firstImportMirrorsListsWithoutRemindersAndSkipsOldHiddenTasks() = runBlocking {
        server.serverAdd("L1", "Milk", notes = "2 l", due = "2026-10-25T00:00:00.000Z")
        server.serverAdd("L1", "Done recently", completed = daysAgo(2), hidden = true)
        server.serverAdd("L1", "Done long ago", completed = daysAgo(40), hidden = true)
        server.serverAdd("L2", "Report")

        assertTrue(sync.run() is SyncOutcome.Done)

        val google = env.db.calendarDao().getBySource(CalendarSource.GOOGLE_TASKS.name)
        assertEquals(listOf("My Tasks", "Work"), google.map { it.name })
        val colors = env.db.calendarDao().getAll().map { it.colorKey }
        assertEquals(colors.size, colors.toSet().size)
        assertEquals(setOf("Milk", "Done recently", "Report"), taskDao.getAll().map { it.title }.toSet())
        val milk = taskDao.getAll().first { it.title == "Milk" }
        assertEquals(LocalDate.of(2026, 10, 25).toEpochDay(), milk.dueDay)
        assertEquals("2 l", milk.note)
        assertNull(milk.dueMinute)
        assertTrue(milk.reminders.isEmpty())
        assertTrue(server.writes.isEmpty())
        assertTrue(dao.pendingUpserts().isEmpty())
        assertEquals(GoogleTasksStatus.Ok, state.status)
        assertEquals(clock.millis, state.lastSyncAt)
    }

    @Test
    fun pagesAreFollowed() = runBlocking {
        server.pageSize = 1
        repeat(3) { server.serverAdd("L1", "Task $it") }
        sync.run()
        assertEquals(3, taskDao.getAll().size)
        assertTrue(server.calls.contains("GET L1 page=2"))
    }

    @Test
    fun incrementalPullIsIdempotentAndKeepsLocalTimeAndReminders() = runBlocking {
        val remoteId = server.serverAdd("L1", "Dentist", due = "2026-10-30T00:00:00.000Z")
        sync.run()
        val id = local(remoteId)!!.id
        edit(id) { it.copy(dueTime = LocalTime.of(9, 30), reminders = listOf(15)) }
        assertTrue("time and reminders stay local", dao.pendingUpserts().isEmpty())

        sync.run()
        assertTrue(server.calls.any { it.startsWith("GET L1 since=") })
        assertTrue(server.writes.isEmpty())

        server.serverEdit(remoteId) { it.copy(title = "Dentist Dr. Weiss") }
        sync.run()
        val task = taskDao.getById(id)!!
        assertEquals("Dentist Dr. Weiss", task.title)
        assertEquals(9 * 60 + 30, task.dueMinute)
        assertEquals(listOf(15), task.reminders)
        assertEquals(1, taskDao.getAll().size)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun changesOnBothSidesAreMergedPerField() = runBlocking {
        val remoteId = server.serverAdd("L1", "Plan trip")
        sync.run()
        val id = local(remoteId)!!.id
        edit(id) { it.copy(note = "Book train") }
        clock.advance(1_000)
        server.serverEdit(remoteId) { it.copy(title = "Plan holiday") }

        sync.run()

        val task = taskDao.getById(id)!!
        assertEquals("Plan holiday", task.title)
        assertEquals("Book train", task.note)
        assertEquals(listOf("PATCH L1/$remoteId"), server.writes)
        assertEquals(setOf("notes"), server.bodies.single().keys)
        assertEquals("Book train", server.task(remoteId)!!.notes)
        assertEquals("Plan holiday", server.task(remoteId)!!.title)
    }

    @Test
    fun conflictingEditsOfOneFieldGoToTheNewerSide() = runBlocking {
        val remoteId = server.serverAdd("L1", "Original")
        sync.run()
        val id = local(remoteId)!!.id

        edit(id) { it.copy(title = "Local first") }
        clock.advance(1_000)
        server.serverEdit(remoteId) { it.copy(title = "Remote later") }
        sync.run()
        assertEquals("Remote later", taskDao.getById(id)!!.title)
        assertTrue(server.writes.isEmpty())

        server.serverEdit(remoteId) { it.copy(title = "Remote first") }
        edit(id) { it.copy(title = "Local later") }
        sync.run()
        assertEquals("Local later", taskDao.getById(id)!!.title)
        assertEquals("Local later", server.task(remoteId)!!.title)
    }

    @Test
    fun completionRoundTrips() = runBlocking {
        val remoteId = server.serverAdd("L1", "Water plants", due = "2026-10-23T00:00:00.000Z")
        sync.run()
        val id = local(remoteId)!!.id
        clock.advance(1_000)
        tasks.setDone(id, true)
        sync.run()
        assertEquals(GoogleTaskMapper.STATUS_DONE, server.task(remoteId)!!.status)
        assertEquals(taskDao.getById(id)!!.completedAt, GoogleTaskMapper.parseTime(server.task(remoteId)!!.completed!!))

        clock.advance(1_000)
        tasks.setDone(id, false)
        sync.run()
        assertEquals(GoogleTaskMapper.STATUS_OPEN, server.task(remoteId)!!.status)
        assertEquals(JsonNull, server.bodies.last()["completed"])
        assertTrue(dao.pendingUpserts().isEmpty())
    }

    @Test
    fun remoteDeletionRemovesCleanTasksButLocalEditsWin() = runBlocking {
        val clean = server.serverAdd("L1", "Clean")
        val edited = server.serverAdd("L1", "Edited")
        sync.run()
        val cleanId = local(clean)!!.id
        val editedId = local(edited)!!.id
        edit(editedId) { it.copy(title = "Edited here") }
        server.serverDelete(clean)
        server.serverDelete(edited)

        sync.run()

        assertNull(taskDao.getById(cleanId))
        assertEquals("Edited here", taskDao.getById(editedId)!!.title)
        val reinserted = server.live("L1").single()
        assertEquals("Edited here", reinserted.title)
        assertEquals(reinserted.id, dao.link(editedId)!!.remoteId)
    }

    @Test
    fun undoInsideTheGraceWindowSendsNoDelete() = runBlocking {
        val remoteId = server.serverAdd("L1", "Call mum")
        sync.run()
        val id = local(remoteId)!!.id
        val removed = tasks.delete(id)!!
        clock.advance(5_000)

        val outcome = sync.run() as SyncOutcome.Done
        assertNotNull(outcome.nextRunIn)
        assertTrue(outcome.nextRunIn!! in 140_000L..GoogleTasksSync.DELETE_GRACE)
        assertTrue(server.writes.isEmpty())

        tasks.restore(removed)
        clock.advance(GoogleTasksSync.DELETE_GRACE)
        sync.run()
        assertTrue(server.writes.isEmpty())
        assertNotNull(server.task(remoteId))
        assertEquals(remoteId, dao.link(id)!!.remoteId)
        assertTrue(dao.pendingDeletes().isEmpty())
    }

    @Test
    fun deleteIsSentAfterTheGraceWindowAndUndoAfterwardsInsertsAgain() = runBlocking {
        val remoteId = server.serverAdd("L1", "Call mum")
        sync.run()
        val id = local(remoteId)!!.id
        val removed = tasks.delete(id)!!
        clock.advance(GoogleTasksSync.DELETE_GRACE + 1_000)

        sync.run()
        assertEquals(listOf("DELETE L1/$remoteId"), server.writes)
        assertNull(server.task(remoteId))
        assertNull(dao.link(id))

        tasks.restore(removed)
        sync.run()
        val again = server.live("L1").single()
        assertEquals("Call mum", again.title)
        assertNotEquals(remoteId, again.id)
        assertEquals(again.id, dao.link(id)!!.remoteId)
        assertEquals(1, taskDao.getAll().size)
    }

    @Test
    fun lostInsertAnswerCreatesNoDuplicate() = runBlocking {
        sync.run()
        val id = tasks.create(Task(calendarId = calendarOf("L1"), title = "Buy bread", dueDate = LocalDate.of(2026, 10, 24)))
        server.dropNextInsertAnswer = true

        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Offline), sync.run())
        assertEquals(GoogleTasksStatus.Offline, state.status)
        assertEquals(1, server.live("L1").size)

        assertTrue(sync.run() is SyncOutcome.Done)
        val remote = server.live("L1").single()
        assertEquals(1, server.calls.count { it.startsWith("POST") })
        assertEquals(remote.id, dao.link(id)!!.remoteId)
        assertNull(dao.link(id)!!.insertStartedAt)
        assertEquals(1, taskDao.getAll().size)
        assertTrue(dao.pendingUpserts().isEmpty())
    }

    @Test
    fun moveBetweenListsKeepsTheRemoteTask() = runBlocking {
        val remoteId = server.serverAdd("L1", "Slides")
        sync.run()
        val id = local(remoteId)!!.id
        val work = calendarOf("L2")
        edit(id) { it.copy(calendarId = work) }

        sync.run()

        assertEquals(listOf("MOVE L1/$remoteId -> L2"), server.writes)
        assertEquals("L2", server.listIdOf(remoteId))
        assertEquals("L2", dao.link(id)!!.remoteListId)
        assertTrue(dao.pendingUpserts().isEmpty())
        assertEquals(id, local(remoteId)!!.id)
    }

    @Test
    fun failedMoveFallsBackToInsertAndDelete() = runBlocking {
        val remoteId = server.serverAdd("L1", "Slides")
        sync.run()
        val id = local(remoteId)!!.id
        val work = calendarOf("L2")
        edit(id) { it.copy(calendarId = work, title = "Slides v2") }
        server.fault = { if (it == "move") TasksApiException(400) else null }

        sync.run()

        assertEquals(listOf("MOVE L1/$remoteId -> L2", "POST L2", "DELETE L1/$remoteId"), server.writes)
        val copy = server.live("L2").single()
        assertEquals("Slides v2", copy.title)
        assertTrue(server.live("L1").isEmpty())
        assertEquals(copy.id, dao.link(id)!!.remoteId)
        assertTrue(dao.pendingUpserts().isEmpty())
        assertTrue(dao.pendingDeletes().isEmpty())
    }

    @Test
    fun remoteMoveFollowsTheTask() = runBlocking {
        val remoteId = server.serverAdd("L1", "Slides")
        sync.run()
        val id = local(remoteId)!!.id
        server.serverMove(remoteId, "L2")
        clock.advance(GoogleTasksSync.FULL_PULL_INTERVAL + 1)

        sync.run()

        assertEquals(calendarOf("L2"), taskDao.getById(id)!!.calendarId)
        assertEquals("L2", dao.link(id)!!.remoteListId)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun movingIntoALocalCalendarDeletesOnlyAtGoogle() = runBlocking {
        val remoteId = server.serverAdd("L1", "Private")
        sync.run()
        val id = local(remoteId)!!.id
        edit(id) { it.copy(calendarId = personal) }

        sync.run()

        assertEquals(listOf("DELETE L1/$remoteId"), server.writes)
        assertEquals(personal, taskDao.getById(id)!!.calendarId)
        assertNull(dao.link(id))
    }

    @Test
    fun listDeletedAtGoogleRescuesUnsentTasksWithoutUploading() = runBlocking {
        val clean = server.serverAdd("L2", "Clean")
        val edited = server.serverAdd("L2", "Edited")
        sync.run()
        val work = calendarOf("L2")
        val cleanId = local(clean)!!.id
        val editedId = local(edited)!!.id
        edit(editedId) { it.copy(note = "important") }
        val newId = tasks.create(Task(calendarId = work, title = "New"))
        server.removeList("L2")

        sync.run()

        assertNull(env.db.calendarDao().getById(work))
        assertNull(taskDao.getById(cleanId))
        assertEquals(personal, taskDao.getById(editedId)!!.calendarId)
        assertEquals(personal, taskDao.getById(newId)!!.calendarId)
        assertNull(dao.link(editedId))
        assertEquals(2, state.rescuedCount)
        assertEquals("Personal", state.rescuedTo)
        assertTrue(server.writes.isEmpty())
        assertEquals(listOf("L1"), dao.lists().map { it.remoteId })
    }

    @Test
    fun editDuringUploadStaysDirtyAndIsSentNext() = runBlocking {
        val remoteId = server.serverAdd("L1", "Essay")
        sync.run()
        val id = local(remoteId)!!.id
        edit(id) { it.copy(title = "Essay draft") }
        var edited = false
        server.afterPatch = {
            if (!edited) {
                edited = true
                edit(id) { it.copy(title = "Essay final") }
            }
        }

        sync.run()

        assertEquals("Essay final", taskDao.getById(id)!!.title)
        assertEquals("Essay final", server.task(remoteId)!!.title)
        assertEquals(2, server.calls.count { it.startsWith("PATCH") })
        assertTrue(dao.pendingUpserts().isEmpty())
    }

    @Test
    fun serverNormalisationCausesNoPingPong() = runBlocking {
        sync.run()
        server.normaliseTitle = { it.trim() }
        val id = tasks.create(Task(calendarId = calendarOf("L1"), title = "  Milk  "))

        sync.run()
        sync.run()

        assertEquals("Milk", taskDao.getById(id)!!.title)
        assertEquals(listOf("POST L1"), server.writes)
        assertTrue(dao.pendingUpserts().isEmpty())
    }

    @Test
    fun rejectedTokenIsClearedAndRefreshedOnce() = runBlocking {
        server.validTokens.clear()
        server.validTokens += "token-2"

        assertTrue(sync.run() is SyncOutcome.Done)
        assertEquals(listOf("token-1"), auth.cleared)
    }

    @Test
    fun secondRejectedTokenNeedsReauth() = runBlocking {
        server.validTokens.clear()

        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.NeedsReauth), sync.run())
        assertEquals(listOf("token-1"), auth.cleared)
        assertEquals(GoogleTasksStatus.NeedsReauth, state.status)
    }

    @Test
    fun errorsMapToStatuses() = runBlocking {
        server.fault = { TasksApiException(403, listOf("accessNotConfigured")) }
        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.ApiDisabled), sync.run())
        server.fault = { TasksApiException(403, listOf("insufficientPermissions")) }
        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.NeedsReauth), sync.run())
        server.fault = { TasksApiException(403, listOf("rateLimitExceeded")) }
        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Failed(403)), sync.run())
        server.fault = { TasksApiException(503) }
        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Failed(503)), sync.run())
        server.fault = { IOException("offline") }
        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Offline), sync.run())
        server.fault = { null }

        auth.background = { AuthResult.NeedsConsent(null) }
        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.NeedsReauth), sync.run())
        auth.background = { AuthResult.Failed(GoogleTasksStatus.SetupError) }
        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.SetupError), sync.run())
        auth.available = false
        assertEquals(SyncOutcome.Stopped(GoogleTasksStatus.NoPlayServices), sync.run())
        assertEquals(GoogleTasksStatus.NoPlayServices, state.status)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun rejectedTaskIsParkedUntilTheNextEdit() = runBlocking {
        sync.run()
        val id = tasks.create(Task(calendarId = calendarOf("L1"), title = "Too odd"))
        server.fault = { if (it == "insert") TasksApiException(400, listOf("invalid")) else null }

        assertTrue(sync.run() is SyncOutcome.Done)
        assertEquals(1, state.rejectedCount)
        assertTrue(dao.pendingUpserts().isEmpty())
        assertEquals(1, server.calls.count { it.startsWith("POST") })

        server.fault = { null }
        sync.run()
        assertEquals(1, server.calls.count { it.startsWith("POST") })

        edit(id) { it.copy(title = "Fine now") }
        sync.run()
        assertEquals("Fine now", server.live("L1").single().title)
        assertEquals(0, state.rejectedCount)
    }

    @Test
    fun disconnectRemovesTheMirrorWithoutRemoteCalls() = runBlocking {
        val remoteId = server.serverAdd("L1", "Keep at Google")
        sync.run()
        edit(local(remoteId)!!.id) { it.copy(title = "Unsent") }
        server.fault = { IOException("offline") }
        val callsBefore = server.calls.size

        connection.disconnect(EMAIL)

        // The one attempt to send the pending change failed offline; nothing else went out.
        assertEquals(1, server.calls.size - callsBefore)
        assertTrue(env.db.calendarDao().getBySource(CalendarSource.GOOGLE_TASKS.name).isEmpty())
        assertTrue(dao.lists().isEmpty())
        assertTrue(dao.pendingDeletes().isEmpty())
        assertEquals(listOf(personal), env.db.calendarDao().getAll().map { it.id })
        assertNull(account[EMAIL])
        assertEquals(1, scheduler.cancelled)
        assertEquals("Keep at Google", server.task(remoteId)!!.title)
    }

    @Test
    fun restoredMirrorWithoutAccountIsPurged() = runBlocking {
        server.serverAdd("L1", "From backup")
        sync.run()
        val personalTask = tasks.create(Task(calendarId = personal, title = "Mine"))
        // A restore brings the database back, but not the excluded google_tasks prefs.
        account.clear()
        val callsBefore = server.calls.size

        connection.purgeAfterRestore()

        assertTrue(env.db.calendarDao().getBySource(CalendarSource.GOOGLE_TASKS.name).isEmpty())
        assertEquals(listOf(personalTask), taskDao.getAll().map { it.id })
        assertEquals(callsBefore, server.calls.size)
        assertEquals(SyncOutcome.NotConnected, sync.run())
    }

    @Test
    fun dataResetDisconnectsGoogleTasks() = runBlocking {
        server.serverAdd("L1", "Remote")
        sync.run()
        env.settings.update { it.copy(defaultTaskCalendarId = personal, lastUsedTaskCalendarId = personal) }
        tasks.create(Task(calendarId = personal, title = "Local"))
        val reset = DataReset(env.db, env.db.calendarDao(), env.db.snoozeDao(), env.calendars, env.settings, connection)

        reset.deleteAll("Personal")

        val calendars = env.db.calendarDao().getAll()
        assertEquals(1, calendars.size)
        assertEquals(CalendarSource.LOCAL.name, calendars.single().source)
        assertTrue(taskDao.getAll().isEmpty())
        val settings = env.settings.current()
        assertNull(settings.defaultTaskCalendarId)
        assertNull(settings.lastUsedTaskCalendarId)
        assertEquals(calendars.single().id, settings.defaultCalendarId)
        assertNull(account[EMAIL])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun connectingMakesGooglesDefaultListTheTaskDefaultWithUndo() = runBlocking {
        account.clear()
        env.settings.update { it.copy(lastUsedTaskCalendarId = personal) }

        val result = connection.connected(EMAIL)

        assertTrue(result.outcome is SyncOutcome.Done)
        val change = result.defaultChange!!
        assertEquals(calendarOf("L1"), change.calendarId)
        assertEquals("My Tasks", change.listName)
        assertEquals(calendarOf("L1"), env.settings.current().defaultTaskCalendarId)
        assertNull(env.settings.current().lastUsedTaskCalendarId)
        assertTrue(scheduler.periodic)

        connection.undoDefaultChange(change)
        assertNull(env.settings.current().defaultTaskCalendarId)
        assertEquals(personal, env.settings.current().lastUsedTaskCalendarId)
    }

    @Test
    fun offlineConnectLeavesARunWaitingForTheNetwork() = runBlocking {
        account.clear()
        server.fault = { IOException("offline") }

        val result = connection.connected(EMAIL)

        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Offline), result.outcome)
        assertNull(result.defaultChange)
        assertEquals(listOf(0L), scheduler.requests)
        assertNotNull(account[EMAIL])
    }

    @Test
    fun accountStateSurvivesARestartOfTheApp() {
        account.setStatus(EMAIL, GoogleTasksStatus.Failed(418))
        account.recordRescue(EMAIL, 2, "Personal")
        val reread = GoogleTasksAccounts(env.context.getSharedPreferences("google_tasks_test", Context.MODE_PRIVATE))
        assertEquals(listOf(EMAIL), reread.emails)
        assertEquals(GoogleTasksStatus.Failed(418), reread[EMAIL]!!.status)
        assertEquals(2, reread[EMAIL]!!.rescuedCount)
        account.setSyncing(EMAIL, true)
        assertEquals(GoogleTasksStatus.Syncing, state.status)
    }

    private companion object {
        const val EMAIL = "me@example.com"
    }
}
