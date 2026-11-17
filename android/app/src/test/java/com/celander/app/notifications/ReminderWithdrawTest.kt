package com.celander.app.notifications

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.model.Task
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDateTime

/** A reminder that is already shown disappears when its event or task goes away. */
@RunWith(RobolectricTestRunner::class)
class ReminderWithdrawTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var env: TestEnvironment
    private lateinit var notifier: ReminderNotifier
    private lateinit var scheduler: ReminderScheduler
    private var calendarId = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier = ReminderNotifier(app).also { it.ensureChannels() }
        val db = env.db
        scheduler = ReminderScheduler(app, db, db.calendarDao(), db.eventDao(), db.exceptionDao(), db.taskDao(), db.snoozeDao(), env.settings, notifier, env.time)
        // Handled up to an hour ago, so a reminder due at 10:00 (now is 10:10) is shown.
        app.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit { putLong("last_handled", env.time.now().minusSeconds(3600).toEpochMilli()) }
        env.calendars.ensureDefault("Personal")
        calendarId = env.calendars.first().single().id
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun deletingAnEventWithdrawsItsReminder() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 23, 10, 30), 60).copy(calendarId = calendarId, reminders = listOf(30)))
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })

        env.events.delete(id)
        scheduler.reschedule()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun completingATaskWithdrawsItsReminder() = runBlocking {
        val task = Task(calendarId = calendarId, title = "Call back", dueDate = LocalDateTime.of(2026, 10, 23, 10, 0).toLocalDate(), dueTime = LocalDateTime.of(2026, 10, 23, 10, 0).toLocalTime(), reminders = listOf(0))
        val id = env.tasks.create(task)
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })

        env.tasks.setDone(id, true)
        scheduler.reschedule()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun remindersOfUnchangedEntriesStay() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 23, 10, 30), 60).copy(calendarId = calendarId, reminders = listOf(30)))
        scheduler.deliverDue()
        scheduler.reschedule()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
    }
}
