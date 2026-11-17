package com.celander.app.task.google

import android.content.Context
import androidx.core.content.edit
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Task
import com.celander.app.task.TaskRepository
import com.celander.app.testing.TestEnvironment
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Several Google Tasks accounts side by side, and taking over the single account of earlier versions. */
@RunWith(RobolectricTestRunner::class)
class GoogleTasksAccountsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private val clock = MutableTimeSource(Instant.parse("2026-10-23T08:00:00Z").toEpochMilli())
    private val first = FakeTasksRemote(clock)
    private val second = FakeTasksRemote(clock, idPrefix = "b")
    private val auth = FakeAuthorizer()
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var accounts: GoogleTasksAccounts
    private lateinit var sync: GoogleTasksSync
    private lateinit var tasks: TaskRepository
    private lateinit var connection: GoogleTasksConnection
    private val dao get() = env.db.googleTasksDao()
    private val taskDao get() = env.db.taskDao()

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        first.addList("A1", "My Tasks", default = true)
        second.addList("B1", "Work tasks", default = true)
        second.validTokens.clear()
        second.validTokens += SECOND_TOKEN
        auth.perAccount[OTHER] = { AuthResult.Granted(SECOND_TOKEN) }
        accounts = GoogleTasksAccounts(env.context.getSharedPreferences("google_tasks_accounts_test", Context.MODE_PRIVATE))
        sync = GoogleTasksSync(env.db, accounts, auth, AccountsRemote(first, second), env.settings, clock)
        tasks = TaskRepository(env.db, taskDao, dao, clock)
        connection = GoogleTasksConnection(env.db, accounts, sync, scheduler, env.settings, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private suspend fun calendarOf(listRemoteId: String): Long = dao.lists().first { it.remoteId == listRemoteId }.calendarId

    private suspend fun titlesIn(listRemoteId: String): Set<String> = taskDao.getForCalendar(calendarOf(listRemoteId)).map { it.title }.toSet()

    private suspend fun connectBoth() {
        first.serverAdd("A1", "Milk")
        second.serverAdd("B1", "Report")
        connection.connected(EMAIL)
        connection.connected(OTHER)
    }

    @Test
    fun theSingleAccountOfEarlierVersionsIsTakenOver() {
        val prefs = env.context.getSharedPreferences("google_tasks_legacy", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putString("email", EMAIL)
            putString("state", "FAILED")
            putInt("lastErrorCode", 503)
            putLong("lastSyncAt", 1_000L)
            putInt("rescuedCount", 2)
            putString("rescuedTo", "Personal")
            putInt("rejectedCount", 1)
        }

        val migrated = GoogleTasksAccounts(prefs)

        assertEquals(listOf(GoogleTasksState(EMAIL, GoogleTasksStatus.Failed(503), 1_000L, 2, "Personal", 1)), migrated.state.value)
        assertNull("the old keys are gone", prefs.getString("email", null))
        assertEquals(migrated.state.value, GoogleTasksAccounts(prefs).state.value)
    }

    @Test
    fun accountsSyncIndependentlyAndOneNeedingSignInDoesNotBlockTheOther() = runBlocking {
        connectBoth()
        assertEquals(listOf(EMAIL, OTHER), accounts.emails)
        assertEquals(setOf("Milk"), titlesIn("A1"))
        assertEquals(setOf("Report"), titlesIn("B1"))
        assertEquals(setOf(EMAIL, OTHER), dao.lists().map { it.account }.toSet())

        auth.perAccount[OTHER] = { AuthResult.NeedsConsent(null) }
        first.serverAdd("A1", "Bread")
        clock.advance(1_000)
        tasks.create(Task(calendarId = calendarOf("A1"), title = "Call mum"))

        assertTrue(sync.run() is SyncOutcome.Done)

        assertEquals(GoogleTasksStatus.NeedsReauth, accounts[OTHER]!!.status)
        assertEquals(GoogleTasksStatus.Ok, accounts[EMAIL]!!.status)
        assertEquals(setOf("Milk", "Bread", "Call mum"), titlesIn("A1"))
        assertEquals(setOf("Milk", "Bread", "Call mum"), first.live("A1").map { it.title }.toSet())
        assertEquals("the stopped account keeps its lists", setOf("Report"), titlesIn("B1"))
    }

    @Test
    fun aPassingProblemOfOneAccountRetriesWhileTheOtherSynced() = runBlocking {
        connectBoth()
        second.fault = { TasksApiException(503) }
        first.serverAdd("A1", "Bread")

        assertEquals(SyncOutcome.Retry(GoogleTasksStatus.Failed(503)), sync.run())

        assertEquals(setOf("Milk", "Bread"), titlesIn("A1"))
        assertEquals(GoogleTasksStatus.Ok, accounts[EMAIL]!!.status)
        assertEquals(GoogleTasksStatus.Failed(503), accounts[OTHER]!!.status)
    }

    @Test
    fun removingOneAccountKeepsTheOthersListsAndSync() = runBlocking {
        connectBoth()
        val calendarsBefore = env.db.calendarDao().getBySource(CalendarSource.GOOGLE_TASKS.name).size

        connection.disconnect(OTHER)

        assertEquals(listOf(EMAIL), accounts.emails)
        assertTrue(dao.lists().all { it.account == EMAIL })
        assertEquals(calendarsBefore - 1, env.db.calendarDao().getBySource(CalendarSource.GOOGLE_TASKS.name).size)
        assertEquals(setOf("Milk"), titlesIn("A1"))
        assertTrue(taskDao.getAll().none { it.title == "Report" })
        assertEquals("nothing deleted at Google", setOf("Report"), second.live("B1").map { it.title }.toSet())
        assertEquals(0, scheduler.cancelled)
        assertTrue(scheduler.periodic)

        first.serverAdd("A1", "Bread")
        assertTrue(sync.run() is SyncOutcome.Done)
        assertEquals(setOf("Milk", "Bread"), titlesIn("A1"))

        connection.disconnect(EMAIL)
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun aTaskMovedToAnotherAccountLeavesTheFirstAndArrivesInTheSecond() = runBlocking {
        connectBoth()
        val milk = taskDao.getAll().single { it.title == "Milk" }
        clock.advance(1_000)
        tasks.update(tasks.get(milk.id)!!.copy(calendarId = calendarOf("B1")))

        assertTrue(sync.run() is SyncOutcome.Done)

        assertTrue(first.live("A1").isEmpty())
        assertEquals(setOf("Report", "Milk"), second.live("B1").map { it.title }.toSet())
        assertEquals(OTHER, dao.link(milk.id)!!.account)
        assertTrue(dao.pendingUpserts().isEmpty())
        assertTrue(dao.pendingDeletes().isEmpty())
    }

    @Test
    fun removingAnAccountBeforeAMoveIntoItWasSentKeepsTheTaskAtTheOther() = runBlocking {
        connectBoth()
        val report = taskDao.getAll().single { it.title == "Report" }
        clock.advance(1_000)
        tasks.update(tasks.get(report.id)!!.copy(calendarId = calendarOf("A1")))

        connection.disconnectLocally(EMAIL)
        assertTrue(sync.run() is SyncOutcome.Done)

        assertEquals("nothing deleted at Google", setOf("Report"), second.live("B1").map { it.title }.toSet())
        assertTrue(dao.pendingDeletes().isEmpty())
    }

    @Test
    fun aSecondAccountKeepsTheTaskDefault() = runBlocking {
        val firstResult = connection.connected(EMAIL)
        assertEquals(calendarOf("A1"), firstResult.defaultChange!!.calendarId)

        val secondResult = connection.connected(OTHER)

        assertNull(secondResult.defaultChange)
        assertEquals(calendarOf("A1"), env.settings.current().defaultTaskCalendarId)
    }

    private companion object {
        const val EMAIL = "me@example.com"
        const val OTHER = "work@example.com"
        const val SECOND_TOKEN = "token-b"
    }
}
