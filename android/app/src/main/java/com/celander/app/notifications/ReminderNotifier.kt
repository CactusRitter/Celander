package com.celander.app.notifications

import com.ce.kit.notify.Notifications
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.celander.app.MainActivity
import com.celander.app.R
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.ui.CalendarColor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and posts reminder notifications in the two channels (events, tasks). */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        // Heads-up with sound and vibration; users change this per channel in the system settings,
        // which then win: Android applies these values only when the channel is first created.
        Notifications.ensureChannel(
            context, CHANNEL_EVENTS, context.getString(R.string.channel_events), context.getString(R.string.channel_events_description),
            NotificationManager.IMPORTANCE_HIGH,
        ) {
            enableVibration(true)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, EVENT_SOUND)
        }
        Notifications.ensureChannel(
            context, CHANNEL_TASKS, context.getString(R.string.channel_tasks), context.getString(R.string.channel_tasks_description),
            NotificationManager.IMPORTANCE_HIGH,
        )
    }

    fun showEvent(reminder: DueReminder, occurrence: EventOccurrence, formats: Formats, zone: ZoneId, today: LocalDate) {
        val timing = occurrence.timing
        val date = timing.startDate(zone)
        val time = when (timing) {
            is EventTiming.AllDay -> context.getString(R.string.all_day)
            is EventTiming.Timed -> context.getString(R.string.time_range, timing.start.atZone(zone).format(formats.time), timing.end.atZone(zone).format(formats.time))
        }
        val text = listOf(dayLabel(date, today, formats) + ", " + time, occurrence.location).filter { it.isNotBlank() }.joinToString(" · ")
        post(reminder, CHANNEL_EVENTS, occurrence.title.ifBlank { context.getString(R.string.untitled) }, text, occurrence.colorKey, NotificationCompat.CATEGORY_EVENT)
    }

    fun showTask(reminder: DueReminder, item: TaskItem, formats: Formats, today: LocalDate) {
        val task = item.task
        val due = task.dueDate?.let { date ->
            val day = dayLabel(date, today, formats)
            context.getString(R.string.notification_task_due, if (task.dueTime != null) day + ", " + task.dueTime.format(formats.time) else day)
        }.orEmpty()
        post(reminder, CHANNEL_TASKS, task.title.ifBlank { context.getString(R.string.untitled) }, due, item.colorKey, NotificationCompat.CATEGORY_REMINDER)
    }

    fun cancel(key: String) = manager.cancel(notificationId(key))

    /** The reminders whose notifications are currently shown. */
    fun shown(): List<DueReminder> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNull { it.notification.extras.reminder() }

    private fun dayLabel(date: LocalDate, today: LocalDate, formats: Formats): String = when (date) {
        today -> context.getString(R.string.today)
        today.plusDays(1) -> context.getString(R.string.tomorrow)
        else -> formats.capitalize(date.format(formats.dayMonthShort))
    }

    private fun post(reminder: DueReminder, channel: String, title: String, text: String, colorKey: String, category: String) {
        if (!canPost()) return
        val id = notificationId(reminder.key)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(CalendarColor.fromKey(colorKey).base(dark = false).toArgb())
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(reminder, id))
            .addExtras(reminder.toExtras())
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(ReminderReceiver.ACTION_SNOOZE, reminder, id + 1))
        // Request codes: id opens, id + 1 snoozes, id + 2 is the second action (done or details).
        when (reminder.kind) {
            ReminderKind.TASK -> builder.addAction(0, context.getString(R.string.notification_done), actionIntent(ReminderReceiver.ACTION_DONE, reminder, id + 2))
            ReminderKind.EVENT -> builder.addAction(0, context.getString(R.string.notification_details), openIntent(reminder, id + 2))
        }
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post; the settings show this state.
        }
    }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    private fun openIntent(reminder: DueReminder, requestCode: Int): PendingIntent {
        // The data makes each reminder's intent its own, so request codes of two reminders that
        // happen to meet never hand one reminder's extras to the other.
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_REMINDER)
            .setData(Uri.Builder().scheme(REMINDER_SCHEME).appendPath(reminder.key).appendPath(requestCode.toString()).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putReminder(reminder)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, reminder: DueReminder, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(action).putReminder(reminder)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_EVENTS = "events"
        const val CHANNEL_TASKS = "tasks"

        private val EVENT_SOUND: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** A snoozed reminder replaces the notification it came from. */
        fun notificationId(key: String): Int = key.removePrefix(SNOOZE_PREFIX).hashCode() and 0x3fffffff

        const val SNOOZE_PREFIX = "s:"

        private const val REMINDER_SCHEME = "celander-reminder"
    }
}

const val EXTRA_KEY = "reminder_key"
const val EXTRA_KIND = "reminder_kind"
const val EXTRA_REF = "reminder_ref"
const val EXTRA_OCCURRENCE = "reminder_occurrence"

fun DueReminder.toExtras(): Bundle = Bundle().apply {
    putString(EXTRA_KEY, key)
    putString(EXTRA_KIND, kind.name)
    putLong(EXTRA_REF, refId)
    putLong(EXTRA_OCCURRENCE, occurrenceStart)
}

fun Intent.putReminder(reminder: DueReminder): Intent = putExtras(reminder.toExtras())

/** The reminder an intent was built for, or null for other intents. */
fun Intent.reminder(): DueReminder? = extras?.reminder()

/** The reminder stored in intent or notification extras, or null. */
fun Bundle.reminder(): DueReminder? {
    val key = getString(EXTRA_KEY) ?: return null
    val kind = getString(EXTRA_KIND)?.let { name -> ReminderKind.entries.firstOrNull { it.name == name } } ?: return null
    return DueReminder(key, kind, getLong(EXTRA_REF), getLong(EXTRA_OCCURRENCE), Instant.EPOCH)
}
