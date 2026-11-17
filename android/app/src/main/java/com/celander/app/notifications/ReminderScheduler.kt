package com.celander.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.SnoozeDao
import com.celander.app.core.database.SnoozeEntity
import com.celander.app.core.database.TaskDao
import com.celander.app.core.database.toModel
import com.celander.app.core.model.TaskItem
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.time.TimeSource
import com.celander.app.core.ui.Formats
import com.celander.app.event.zone
import com.celander.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Keeps exactly one alarm set: the next due reminder (PLAN.md, decision 9). When it fires, all due
 * reminders are shown and the following one is planned. Data changes, boot and clock changes replan.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CelanderDatabase,
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val exceptionDao: ExceptionDao,
    private val taskDao: TaskDao,
    private val snoozeDao: SnoozeDao,
    private val settings: SettingsRepository,
    private val notifier: ReminderNotifier,
    private val time: TimeSource,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reminders handled in the last [KEY_RETENTION] ([DueReminder.handledId]), each with the time it was handled. */
    private val handled = context.getSharedPreferences(HANDLED_PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Replans on every change to events, tasks or snoozes while the process lives. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            db.invalidationTracker.createFlow("events", "event_exceptions", "tasks", "snoozes")
                .debounce(CHANGE_DEBOUNCE_MILLIS)
                .collect { reschedule() }
        }
    }

    suspend fun reschedule() = mutex.withLock {
        scheduleNext(time.now())
        withdrawStale()
    }

    /** Shows everything that is due and plans the next alarm. */
    suspend fun deliverDue() = mutex.withLock {
        val now = time.now()
        val upTo = now.plus(EARLY_TOLERANCE)
        val handledUntil = lastHandled(now)
        val oldest = now.minus(MISSED_WINDOW)
        val due = plan(maxOf(handledUntil, oldest), maxOf(wallClockStart(handledUntil), oldest), upTo, now)
        for (reminder in due) {
            val snoozed = reminder.key.startsWith(ReminderNotifier.SNOOZE_PREFIX)
            if (snoozed) snoozeDao.delete(reminder.key)
            show(reminder, now)
            // A snooze is shown once anyway (its row is gone) and may be snoozed again under the same key.
            if (!snoozed) handled.edit { putLong(reminder.handledId, now.toEpochMilli()) }
        }
        prefs.edit {
            putLong(KEY_LAST_HANDLED, upTo.toEpochMilli())
            putString(KEY_ZONE, time.zone().id)
        }
        scheduleNext(now)
    }

    /** "10 minutes later": the notification goes away and comes back as a snoozed reminder. */
    suspend fun snooze(reminder: DueReminder) {
        notifier.cancel(reminder.key)
        val key = ReminderNotifier.SNOOZE_PREFIX + reminder.key.removePrefix(ReminderNotifier.SNOOZE_PREFIX)
        snoozeDao.upsert(SnoozeEntity(key, reminder.kind.name, reminder.refId, reminder.occurrenceStart, time.now().plus(SNOOZE).toEpochMilli()))
        reschedule()
    }

    suspend fun completeTask(reminder: DueReminder) {
        notifier.cancel(reminder.key)
        val now = time.now().toEpochMilli()
        taskDao.setDone(reminder.refId, true, now, now)
    }

    /**
     * Up to when reminders were handled. The first run starts now, so nothing from the past is
     * reminded about. So does a clock set back from a time ahead, or everything up to that time
     * would be skipped; the handled keys keep what was already shown from coming again.
     *
     * The time handled before the clock went back is kept as the peak. Once the clock is past it
     * again, everything up to it counts as handled again: a clock that was set back only for a while
     * (a wrong time at boot, before the network time) brings back nothing it had shown, although
     * its handled keys were dropped meanwhile (see [handledKeys]).
     */
    private fun lastHandled(now: Instant): Instant {
        val stored = prefs.instant(KEY_LAST_HANDLED)
        val peak = prefs.instant(KEY_PEAK)
        if (stored != null && peak != null && !peak.isAfter(now)) {
            val restored = maxOf(stored, peak)
            prefs.edit {
                putLong(KEY_LAST_HANDLED, restored.toEpochMilli())
                remove(KEY_PEAK)
            }
            return restored
        }
        if (stored != null && !stored.isAfter(now.plus(EARLY_TOLERANCE))) return stored
        prefs.edit {
            putLong(KEY_LAST_HANDLED, now.toEpochMilli())
            if (stored == null) {
                putString(KEY_ZONE, time.zone().id)
                remove(KEY_PEAK)
            } else {
                putLong(KEY_PEAK, maxOf(stored, peak ?: stored).toEpochMilli())
            }
        }
        return now
    }

    private fun SharedPreferences.instant(key: String): Instant? = if (contains(key)) Instant.ofEpochMilli(getLong(key, 0)) else null

    /**
     * Where wall-clock reminders (tasks, all-day events) start: before [handledUntil] by as much as
     * a move east since then brought them forward, so none of them is lost. The zone is the one of
     * the last delivery.
     */
    private fun wallClockStart(handledUntil: Instant): Instant {
        val previous = prefs.getString(KEY_ZONE, null)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return handledUntil
        return handledUntil.minus(ReminderPlanner.wallClockShift(previous, time.zone(), handledUntil))
    }

    /**
     * Reminders handled recently ([DueReminder.handledId]); older entries, and ones from a clock that
     * was far off, are dropped.
     */
    private fun handledKeys(now: Instant): Set<String> {
        val entries = handled.all
        val stale = entries.filter { (_, at) -> at !is Long || abs(now.toEpochMilli() - at) > KEY_RETENTION.toMillis() }.keys
        if (stale.isNotEmpty()) handled.edit { stale.forEach { remove(it) } }
        return entries.keys - stale
    }

    /**
     * Takes back shown reminders whose event was deleted or whose occurrence is gone (cancelled, or
     * cut off the series), or whose task was deleted or done.
     */
    private suspend fun withdrawStale() {
        for (reminder in notifier.shown()) {
            val stillValid = when (reminder.kind) {
                ReminderKind.EVENT -> {
                    val event = eventDao.getById(reminder.refId)?.toModel()
                    event != null && ReminderPlanner.isOccurrence(event, exceptionDao.getForEvent(event.id).map { it.toModel(event.zone) }, reminder.occurrenceStart)
                }
                ReminderKind.TASK -> taskDao.getById(reminder.refId)?.done == false
            }
            if (!stillValid) notifier.cancel(reminder.key)
        }
    }

    private suspend fun scheduleNext(now: Instant) {
        val handledUntil = lastHandled(now)
        val next = plan(handledUntil, wallClockStart(handledUntil), now.plus(HORIZON), now).firstOrNull()?.triggerAt
        setAlarm(next ?: now.plus(HORIZON))
    }

    /** Reminders in (`after`, `until`] that were not handled yet; wall-clock ones from [wallClockAfter]. */
    private suspend fun plan(after: Instant, wallClockAfter: Instant, until: Instant, now: Instant): List<DueReminder> {
        val events = eventDao.getAll().map { it.toModel() }
        val zones = events.associate { it.id to it.zone }
        val exceptions = exceptionDao.getAll().groupBy { it.eventId }.mapValues { (eventId, list) ->
            list.map { it.toModel(zones[eventId] ?: time.zone()) }
        }
        val tasks = taskDao.getAll().map { it.toModel() }
        val snoozed = snoozeDao.getAll().mapNotNull { snooze ->
            val kind = ReminderKind.entries.firstOrNull { it.name == snooze.kind } ?: return@mapNotNull null
            DueReminder(snooze.key, kind, snooze.refId, snooze.occurrenceStart, Instant.ofEpochMilli(snooze.triggerAt))
        }
        val done = handledKeys(now)
        return ReminderPlanner.plan(events, exceptions, tasks, snoozed, after, until, time.zone(), wallClockAfter).filter { it.handledId !in done }
    }

    /** Posts [reminder] unless it is stale: long overdue and for something that is already over. */
    private suspend fun show(reminder: DueReminder, now: Instant) {
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        val formats = Formats.of(context, settings.current().timeFormat)
        val recent = !reminder.triggerAt.isBefore(now.minus(LATE_TOLERANCE))
        val calendars = calendarDao.getAll().associate { it.id to it.toModel() }
        when (reminder.kind) {
            ReminderKind.EVENT -> {
                val event = eventDao.getById(reminder.refId)?.toModel() ?: return
                val calendar = calendars[event.calendarId] ?: return
                val exceptions = exceptionDao.getForEvent(event.id).map { it.toModel(event.zone) }
                // Cancelled or cut off the series meanwhile; a snoozed reminder can outlive its occurrence.
                if (!ReminderPlanner.isOccurrence(event, exceptions, reminder.occurrenceStart)) return
                val exception = exceptions.firstOrNull { it.originalStart == reminder.occurrenceStart }
                val occurrence = if (event.isRecurring) OccurrenceCalculator.occurrence(event, calendar, reminder.occurrenceStart, exception)
                else OccurrenceCalculator.single(event, calendar)
                if (!recent && !occurrence.timing.endInstant(zone).isAfter(now)) return
                notifier.showEvent(reminder, occurrence, formats, zone, today)
            }
            ReminderKind.TASK -> {
                val task = taskDao.getById(reminder.refId)?.toModel() ?: return
                if (task.done) return
                val calendar = calendars[task.calendarId] ?: return
                notifier.showTask(reminder, TaskItem(task, calendar), formats, today)
            }
        }
    }

    private fun setAlarm(at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (canScheduleExact(alarms)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } else {
            // Without permission the system may deliver a few minutes late; the settings say so.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    companion object {
        private const val PREFS = "reminders"
        private const val KEY_LAST_HANDLED = "last_handled"

        /** How far reminders were handled before the clock was set back (see [lastHandled]). */
        private const val KEY_PEAK = "handled_peak"

        /** The zone of the last delivery, to tell how far a time zone change moved wall-clock reminders. */
        private const val KEY_ZONE = "zone"

        /** Kept out of backups like [PREFS]: both describe this device's notifications (see backup_rules.xml). */
        private const val HANDLED_PREFS = "reminders_handled"
        private const val CHANGE_DEBOUNCE_MILLIS = 300L
        private val HORIZON: Duration = Duration.ofDays(30)
        private val EARLY_TOLERANCE: Duration = Duration.ofSeconds(5)
        private val LATE_TOLERANCE: Duration = Duration.ofMinutes(15)
        private val MISSED_WINDOW: Duration = Duration.ofDays(1)

        /** Longer than the largest zone difference (26 h), so a move west cannot bring a shown reminder back. */
        private val KEY_RETENTION: Duration = Duration.ofHours(48)
        val SNOOZE: Duration = Duration.ofMinutes(10)

        fun canScheduleExact(alarms: AlarmManager): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    }
}
