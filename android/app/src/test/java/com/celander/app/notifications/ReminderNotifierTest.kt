package com.celander.app.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.celander.app.MainActivity
import com.celander.app.core.ui.Formats
import com.celander.app.testing.BERLIN
import com.celander.app.testing.occurrence
import com.celander.app.testing.taskItem
import com.celander.app.testing.timedEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The two reminder channels and the buttons on event and task notifications. */
@RunWith(RobolectricTestRunner::class)
class ReminderNotifierTest {
    private lateinit var app: Application
    private lateinit var system: NotificationManager
    private lateinit var notifier: ReminderNotifier
    private val formats = Formats(Locale.ENGLISH, use24h = true)
    private val today = LocalDate.of(2026, 11, 3)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        system = app.getSystemService(NotificationManager::class.java)
        notifier = ReminderNotifier(app).also { it.ensureChannels() }
    }

    @Test
    fun eventRemindersPopUpWithSoundAndVibration() {
        val channel = system.getNotificationChannel(ReminderNotifier.CHANNEL_EVENTS)
        assertEquals("Event reminders", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
    }

    @Test
    fun taskDueDatesHaveTheirOwnChannel() {
        val channel = system.getNotificationChannel(ReminderNotifier.CHANNEL_TASKS)
        assertEquals("Task due dates", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun anEventReminderOffersSnoozeAndDetails() {
        val event = timedEvent(7, LocalDateTime.of(2026, 11, 3, 14, 30), 60)
        val reminder = DueReminder("e:7", ReminderKind.EVENT, 7, event.timing.occurrenceKey, Instant.EPOCH)
        notifier.showEvent(reminder, event.occurrence(), formats, BERLIN, today)

        val notification = posted()
        val id = ReminderNotifier.notificationId(reminder.key)
        assertEquals(listOf("In 10 minutes", "Details"), notification.actions.map { it.title.toString() })

        val snooze = shadowOf(notification.actions[0].actionIntent)
        assertTrue(snooze.isBroadcast)
        assertEquals(ReminderReceiver.ACTION_SNOOZE, snooze.savedIntent.action)
        assertEquals(id + 1, snooze.requestCode)

        // Details opens the event like a tap on the notification, through its own pending intent.
        val details = shadowOf(notification.actions[1].actionIntent)
        val content = shadowOf(notification.contentIntent)
        assertTrue(details.isActivity)
        assertEquals(MainActivity.ACTION_OPEN_REMINDER, details.savedIntent.action)
        assertEquals(reminder.refId, details.savedIntent.reminder()?.refId)
        assertEquals(reminder.occurrenceStart, details.savedIntent.reminder()?.occurrenceStart)
        assertEquals(id + 2, details.requestCode)
        assertEquals(id, content.requestCode)
        assertEquals(MainActivity.ACTION_OPEN_REMINDER, content.savedIntent.action)
    }

    @Test
    fun aTaskReminderOffersSnoozeAndDone() {
        val reminder = DueReminder("t:3", ReminderKind.TASK, 3, 0, Instant.EPOCH)
        notifier.showTask(reminder, taskItem(3, today), formats, today)

        val notification = posted()
        assertEquals(listOf("In 10 minutes", "Done"), notification.actions.map { it.title.toString() })
        val done = shadowOf(notification.actions[1].actionIntent)
        assertTrue(done.isBroadcast)
        assertEquals(ReminderReceiver.ACTION_DONE, done.savedIntent.action)
        assertEquals(ReminderNotifier.notificationId(reminder.key) + 2, done.requestCode)
    }

    @Test
    fun theRemindersOfOneEventShareOneNotification() {
        val event = timedEvent(7, LocalDateTime.of(2026, 11, 3, 14, 30), 60)
        val start = event.timing.occurrenceKey
        val anHourBefore = DueReminder("e:7:$start:60", ReminderKind.EVENT, 7, start, Instant.EPOCH)
        val tenMinutesBefore = DueReminder("e:7:$start:10", ReminderKind.EVENT, 7, start, Instant.EPOCH)
        notifier.showEvent(anHourBefore, event.occurrence(), formats, BERLIN, today)
        notifier.showEvent(tenMinutesBefore, event.occurrence(), formats, BERLIN, today)

        // The later reminder replaces the earlier one instead of piling up beside it.
        assertEquals(1, shadowOf(system).size())
        assertEquals(ReminderNotifier.notificationId(anHourBefore.key), ReminderNotifier.notificationId(tenMinutesBefore.key))
        // Snoozing keeps the same notification, and another event or another day gets its own.
        assertEquals(ReminderNotifier.notificationId(tenMinutesBefore.key), ReminderNotifier.notificationId(ReminderNotifier.SNOOZE_PREFIX + tenMinutesBefore.key))
        assertTrue(ReminderNotifier.notificationId("e:8:$start:10") != ReminderNotifier.notificationId(tenMinutesBefore.key))
        assertTrue(ReminderNotifier.notificationId("e:7:${start + 1}:10") != ReminderNotifier.notificationId(tenMinutesBefore.key))
    }

    private fun posted(): Notification = shadowOf(system).allNotifications.single()
}
