package com.celander.app.task.google

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.database.GoogleTaskListEntity
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Task
import com.celander.app.task.TaskRepository
import com.celander.app.testing.TestEnvironment
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleTasksSyncTriggerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private val clock = MutableTimeSource(Instant.parse("2026-10-23T08:00:00Z").toEpochMilli())
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var account: GoogleTasksAccounts
    private lateinit var trigger: GoogleTasksSyncTrigger
    private lateinit var tasks: TaskRepository
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var list = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        account = GoogleTasksAccounts(env.context.getSharedPreferences("google_tasks_trigger", Context.MODE_PRIVATE))
        val sync = GoogleTasksSync(env.db, account, FakeAuthorizer(), FakeTasksRemote(clock), env.settings, clock)
        val connection = GoogleTasksConnection(env.db, account, sync, scheduler, env.settings, scope)
        trigger = GoogleTasksSyncTrigger(account, env.db.googleTasksDao(), connection, scheduler, clock)
        tasks = TaskRepository(env.db, env.db.taskDao(), env.db.googleTasksDao(), clock)
        list = env.db.calendarDao().insert(CalendarEntity(name = "My Tasks", colorKey = "teal", createdAt = 0, source = CalendarSource.GOOGLE_TASKS.name))
        env.db.googleTasksDao().upsertList(GoogleTaskListEntity(calendarId = list, account = EMAIL, remoteId = "L1"))
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private suspend fun awaitRequests(count: Int) = withTimeout(10_000) {
        while (synchronized(scheduler.requests) { scheduler.requests.size } < count) delay(50)
    }

    /** Longer than the debounce, to show that nothing else is coming. */
    private suspend fun settle() = delay(GoogleTasksSyncTrigger.DEBOUNCE + 1_000)

    @Test
    fun localChangeWhileConnectedRequestsADelayedSync() = runBlocking {
        account.connect(EMAIL)
        trigger.start(scope, owner.lifecycle)
        settle()
        assertTrue(scheduler.requests.isEmpty())

        tasks.create(Task(calendarId = list, title = "New"))

        awaitRequests(1)
        assertEquals(listOf(TaskSyncScheduler.LOCAL_CHANGE_DELAY), scheduler.requests)
    }

    @Test
    fun nothingIsRequestedWhileDisconnected() = runBlocking {
        trigger.start(scope, owner.lifecycle)
        // A restored mirror without its account is removed first; a new list stays local.
        settle()
        assertTrue(env.db.googleTasksDao().lists().isEmpty())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        settle()
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun comingToTheForegroundSyncsWhenTheLastSyncIsOld() = runBlocking {
        account.connect(EMAIL)
        account.recordSuccess(EMAIL, clock.millis - 60_000, 0)
        trigger.start(scope, owner.lifecycle)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue("synced a minute ago", scheduler.requests.isEmpty())

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        clock.advance(GoogleTasksSyncTrigger.FOREGROUND_STALE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf(0L), scheduler.requests)
    }

    @Test
    fun workManagerSchedulerKeepsOneWaitingRunAndCancelsBoth() {
        val context = env.context
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val workManager = WorkManager.getInstance(context)
        val real = WorkManagerTaskSyncScheduler(context)

        real.requestSoon(TaskSyncScheduler.LOCAL_CHANGE_DELAY)
        val first = workManager.getWorkInfosForUniqueWork(WorkManagerTaskSyncScheduler.ONE_TIME).get().single()
        real.requestSoon(0)
        val kept = workManager.getWorkInfosForUniqueWork(WorkManagerTaskSyncScheduler.ONE_TIME).get().single()
        assertEquals(first.id, kept.id)
        assertEquals(WorkInfo.State.ENQUEUED, kept.state)
        assertEquals(androidx.work.NetworkType.CONNECTED, kept.constraints.requiredNetworkType)

        real.schedulePeriodic()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfosForUniqueWork(WorkManagerTaskSyncScheduler.PERIODIC).get().single().state)

        real.cancelAll()
        val states = (workManager.getWorkInfosForUniqueWork(WorkManagerTaskSyncScheduler.ONE_TIME).get() +
            workManager.getWorkInfosForUniqueWork(WorkManagerTaskSyncScheduler.PERIODIC).get()).map { it.state }
        assertTrue(states.all { it == WorkInfo.State.CANCELLED })
    }

    private companion object {
        const val EMAIL = "me@example.com"
    }
}
