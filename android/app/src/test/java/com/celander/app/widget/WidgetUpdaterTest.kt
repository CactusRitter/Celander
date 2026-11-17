package com.celander.app.widget

import android.app.AlarmManager
import com.celander.app.testing.TestEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

/** What makes the widgets refresh. */
@RunWith(RobolectricTestRunner::class)
class WidgetUpdaterTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var env: TestEnvironment

    @Before
    fun setUp() {
        env = TestEnvironment(folder.root)
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun hidingACalendarRefreshesTheWidgets() = runBlocking {
        val changes = Channel<Set<String>>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.IO) { env.db.invalidationTracker.createFlow(*WidgetUpdater.WIDGET_TABLES).collect { changes.send(it) } }
        // The first emission says the tables are observed.
        withTimeout(TIMEOUT_MILLIS) { changes.receive() }
        env.calendars.ensureDefault("Personal")
        env.calendars.setVisible(env.calendars.first().single().id, false)
        val changed = withTimeout(TIMEOUT_MILLIS) { changes.receive() }
        watcher.cancel()
        assertTrue(changed.toString(), "calendars" in changed)
    }

    @Test
    fun theNowOrNextCardTurnsOverOnTheMinuteWithExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val at = env.time.now().plus(Duration.ofMinutes(20))
        WidgetUpdater.scheduleRefresh(env.context, at)
        val alarm = shadowOf(env.context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()!!
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(at.toEpochMilli(), alarm.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    }

    @Test
    fun withoutExactAlarmsTheCardTurnsOverWithinAMinute() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val at = env.time.now().plus(Duration.ofMinutes(20))
        WidgetUpdater.scheduleRefresh(env.context, at)
        val alarm = shadowOf(env.context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()!!
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(Duration.ofMinutes(1).toMillis(), alarm.windowLengthMs)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
