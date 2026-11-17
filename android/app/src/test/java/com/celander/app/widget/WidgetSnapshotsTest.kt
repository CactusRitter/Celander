package com.celander.app.widget

import com.celander.app.calendar.data.CalendarContentRepository
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.calendar.device.DeviceCalendarSource
import com.celander.app.core.model.Task
import com.celander.app.core.time.TimeSource
import com.celander.app.settings.SettingsRepository
import com.celander.app.task.TaskRepository
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A new widget session (every data change starts one) begins with what was read before it composes,
 * so its first picture is not an empty list.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetSnapshotsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private lateinit var deps: WidgetEntryPoint
    private val today = LocalDate.of(2026, 10, 23)

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
        val content = CalendarContentRepository(env.db.calendarDao(), env.db.eventDao(), env.db.exceptionDao(), env.db.taskDao(), DeviceCalendarSource.None, Dispatchers.Default)
        deps = object : WidgetEntryPoint {
            override fun content(): CalendarContentRepository = content
            override fun calendars(): CalendarsRepository = env.calendars
            override fun tasks(): TaskRepository = env.tasks
            override fun settings(): SettingsRepository = env.settings
            override fun time(): TimeSource = env.time
            override fun device(): DeviceCalendarSource = DeviceCalendarSource.None
        }
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun aNewSessionStartsWithTheEntriesReadBeforeIt() = runBlocking {
        val calendar = env.calendars.first().single().id
        env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 23, 9, 30), 15).copy(calendarId = calendar, title = "Stand-up"))
        env.tasks.create(Task(calendarId = calendar, title = "Shopping"))
        val end = today.plusMonths(3)

        WidgetSnapshots.preload(deps, listOf(today to end), BERLIN, tasks = true)

        val window = WidgetSnapshots.lastWindow(today, end, BERLIN)!!
        assertEquals(listOf("Stand-up"), window.day(today).timed.map { it.title })
        assertEquals(listOf("Shopping"), WidgetSnapshots.lastTaskItems()!!.map { it.task.title })
    }

    @Test
    fun aNewTaskReplacesWhatTheWidgetsKeep() = runBlocking {
        val calendar = env.calendars.first().single().id
        WidgetSnapshots.preload(deps, emptyList(), BERLIN, tasks = true)
        env.tasks.create(Task(calendarId = calendar, title = "Call the dentist"))

        WidgetSnapshots.taskItems(deps).first { items -> items.any { it.task.title == "Call the dentist" } }
        assertEquals(listOf("Call the dentist"), WidgetSnapshots.lastTaskItems()!!.map { it.task.title })
    }
}
