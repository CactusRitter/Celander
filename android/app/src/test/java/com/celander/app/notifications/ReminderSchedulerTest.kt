package com.celander.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.Task
import com.celander.app.core.time.TimeSource
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.allDayEvent
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Reminders across time zone and clock changes, and of occurrences moved or cut off their series. */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    @get:Rule val folder = TemporaryFolder()

    /** A clock that can travel and be set. */
    private class Clock(var instant: Instant, var zoneId: ZoneId) : TimeSource {
        override fun now(): Instant = instant

        override fun zone(): ZoneId = zoneId
    }

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private lateinit var env: TestEnvironment
    private lateinit var app: Application
    private lateinit var clock: Clock
    private lateinit var notifier: ReminderNotifier
    private lateinit var scheduler: ReminderScheduler
    private var calendarId = 0L

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier = ReminderNotifier(app).also { it.ensureChannels() }
        // Friday, October 23, 10:10 in Berlin, handled up to an hour ago.
        clock = Clock(env.time.now(), BERLIN)
        val db = env.db
        scheduler = ReminderScheduler(app, db, db.calendarDao(), db.eventDao(), db.exceptionDao(), db.taskDao(), db.snoozeDao(), env.settings, notifier, clock)
        handledUntil(clock.instant.minusSeconds(3600), BERLIN)
        env.calendars.ensureDefault("Personal")
        calendarId = env.calendars.first().single().id
    }

    @After
    fun tearDown() = env.close()

    private fun handledUntil(at: Instant, zone: ZoneId) {
        app.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit {
            putLong("last_handled", at.toEpochMilli())
            putString("zone", zone.id)
        }
    }

    private fun task(due: LocalDateTime) =
        Task(calendarId = calendarId, title = "Call back", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminders = listOf(0))

    private fun dismissAll() = notifier.shown().forEach { notifier.cancel(it.key) }

    private fun nextAlarm(): Long = shadowOf(app.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()!!.triggerAtMs

    @Test
    fun aReminderShownBeforeAFlightWestDoesNotComeAgain() = runBlocking {
        val id = env.tasks.create(task(LocalDateTime.of(2026, 10, 23, 10, 0)))
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
        dismissAll()

        // In New York, six hours behind, 10:00 on the wall clock comes once more.
        clock.zoneId = newYork
        clock.instant = LocalDateTime.of(2026, 10, 23, 10, 5).atZone(newYork).toInstant()
        scheduler.reschedule()
        scheduler.deliverDue()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun aReminderAFlightEastBringsForwardIsShownOnce() = runBlocking {
        // In New York at 6:30 everything so far is handled; the task is due at 9:00.
        clock.zoneId = newYork
        clock.instant = LocalDateTime.of(2026, 10, 23, 6, 30).atZone(newYork).toInstant()
        handledUntil(clock.instant, newYork)
        val id = env.tasks.create(task(LocalDateTime.of(2026, 10, 23, 9, 0)))

        // At 7:00 New York time the phone is in Berlin, where 9:00 was four hours ago.
        clock.zoneId = BERLIN
        clock.instant = LocalDateTime.of(2026, 10, 23, 7, 0).atZone(newYork).toInstant()
        scheduler.reschedule()
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })

        dismissAll()
        clock.instant = clock.instant.plus(Duration.ofMinutes(5))
        scheduler.deliverDue()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun anAllDayReminderShownBeforeAFlightWestDoesNotComeAgain() = runBlocking {
        // -600: on the day at 10:00 on the wall clock.
        val id = env.events.create(allDayEvent(0, LocalDate.of(2026, 10, 23)).copy(calendarId = calendarId, reminders = listOf(-600)))
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
        dismissAll()

        // 10:00 comes once more in New York; it is the same reminder.
        clock.zoneId = newYork
        clock.instant = LocalDateTime.of(2026, 10, 23, 10, 5).atZone(newYork).toInstant()
        scheduler.reschedule()
        scheduler.deliverDue()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun anOccurrenceMovedAfterItsReminderRemindsAgain() = runBlocking {
        // Daily at 10:30, reminded at 10:00; today's reminder is shown at 10:10.
        val series = timedEvent(0, LocalDateTime.of(2026, 10, 20, 10, 30), 60, rrule = "FREQ=DAILY").copy(calendarId = calendarId, reminders = listOf(30))
        val id = env.events.create(series)
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
        dismissAll()

        // Only today's occurrence moves to 11:00, so it reminds at 10:30 once more.
        val today = LocalDateTime.of(2026, 10, 23, 10, 30).atZone(BERLIN).toInstant()
        val moved = EventTiming.Timed(today.plus(Duration.ofMinutes(30)), today.plus(Duration.ofMinutes(90)), BERLIN)
        env.events.saveException(EventException(eventId = id, originalStart = today.toEpochMilli(), timing = moved))
        scheduler.reschedule()
        assertEquals(today.toEpochMilli(), nextAlarm())

        clock.instant = today
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
    }

    @Test
    fun aClockSetBackDoesNotRemindTwice() = runBlocking {
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 23, 10, 30), 60).copy(calendarId = calendarId, reminders = listOf(30)))
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
        dismissAll()

        // The clock goes back a quarter of an hour, so 10:00 lies ahead again; it was shown already.
        clock.instant = LocalDateTime.of(2026, 10, 23, 9, 55).atZone(BERLIN).toInstant()
        scheduler.reschedule()
        clock.instant = LocalDateTime.of(2026, 10, 23, 10, 1).atZone(BERLIN).toInstant()
        scheduler.deliverDue()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun aClockBrieflySetFarBackBringsNothingBack() = runBlocking {
        val shown = env.tasks.create(task(LocalDateTime.of(2026, 10, 23, 10, 0)))
        scheduler.deliverDue()
        assertEquals(listOf(shown), notifier.shown().map { it.refId })
        dismissAll()
        val later = env.tasks.create(task(LocalDateTime.of(2026, 10, 23, 10, 12)))

        // At boot the clock is a week behind for a moment, until the network time corrects it.
        val correct = clock.instant
        clock.instant = correct.minus(Duration.ofDays(7))
        scheduler.reschedule()
        clock.instant = correct.plus(Duration.ofMinutes(5))
        scheduler.reschedule()
        scheduler.deliverDue()
        assertEquals(listOf(later), notifier.shown().map { it.refId })
    }

    @Test
    fun aClockSetBackStillRemindsOfWhatLiesAhead() = runBlocking {
        // The clock ran a week ahead, so everything up to then counted as handled.
        handledUntil(clock.instant.plus(Duration.ofDays(7)), BERLIN)
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 23, 11, 0), 60).copy(calendarId = calendarId, reminders = listOf(30)))
        scheduler.reschedule()
        assertEquals(LocalDateTime.of(2026, 10, 23, 10, 30).atZone(BERLIN).toInstant().toEpochMilli(), nextAlarm())

        clock.instant = LocalDateTime.of(2026, 10, 23, 10, 30).atZone(BERLIN).toInstant()
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
    }

    @Test
    fun endingTheSeriesBeforeAnOccurrenceWithdrawsItsReminder() = runBlocking {
        val series = timedEvent(0, LocalDateTime.of(2026, 10, 20, 10, 30), 60, rrule = "FREQ=DAILY").copy(calendarId = calendarId, reminders = listOf(30))
        val id = env.events.create(series)
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })

        // "Delete this and following" from today on: the rule now ends yesterday.
        env.events.update(series.copy(id = id, rrule = "FREQ=DAILY;UNTIL=20261022T235959Z"))
        scheduler.reschedule()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun aSnoozedReminderOfAnOccurrenceCutOffTheSeriesStaysQuiet() = runBlocking {
        val series = timedEvent(0, LocalDateTime.of(2026, 10, 20, 10, 30), 60, rrule = "FREQ=DAILY").copy(calendarId = calendarId, reminders = listOf(30))
        val id = env.events.create(series)
        scheduler.deliverDue()
        scheduler.snooze(notifier.shown().single())
        assertEquals(emptyList<DueReminder>(), notifier.shown())

        env.events.update(series.copy(id = id, rrule = "FREQ=DAILY;UNTIL=20261022T235959Z"))
        clock.instant = clock.instant.plus(ReminderScheduler.SNOOZE).plusSeconds(60)
        scheduler.deliverDue()
        assertEquals(emptyList<DueReminder>(), notifier.shown())
    }

    @Test
    fun aSnoozedReminderComesBackAfterTheSnooze() = runBlocking {
        val id = env.tasks.create(task(LocalDateTime.of(2026, 10, 23, 10, 0)))
        scheduler.deliverDue()
        scheduler.snooze(notifier.shown().single())
        clock.instant = clock.instant.plus(ReminderScheduler.SNOOZE).plusSeconds(60)
        scheduler.deliverDue()
        assertEquals(listOf(id), notifier.shown().map { it.refId })
    }
}
