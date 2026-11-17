package com.celander.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.celander.app.calendar.device.DeviceCalendarSource
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.di.ApplicationScope
import com.celander.app.notifications.InjectedReceiver
import com.celander.app.notifications.ReminderScheduler
import com.celander.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps the home screen widgets current: after every change to calendars, events and tasks, after
 * changes to the settings they show, and at midnight (plus clock, time zone and language changes,
 * see [WidgetRefreshReceiver]). Nothing happens while no widget is placed.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CelanderDatabase,
    private val settings: SettingsRepository,
    private val device: DeviceCalendarSource,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            merge(
                // Visibility and color of the phone's calendars live in device_calendar_prefs, which the provider does not report.
                db.invalidationTracker.createFlow(*WIDGET_TABLES).map { },
                device.changes,
                settings.settings
                    .map { listOf(it.themeMode, it.accentPalette, it.widgetTransparency, it.weekStart, it.showWeekNumbers, it.timeFormat) }
                    .distinctUntilChanged()
                    .map { },
            ).debounce(UPDATE_DEBOUNCE_MILLIS).collect { updateAll(context) }
        }
        scope.launch { scheduleNextDay(context) }
        // Outside the app, a content trigger refreshes the widgets on changes in the phone's calendars.
        scope.launch { device.status.distinctUntilChanged().collect { DeviceCalendarRefreshWorker.enqueueIfNeeded(context) } }
    }

    companion object {
        const val ACTION_REFRESH = "com.celander.app.action.REFRESH_WIDGETS"

        /** The tables whose changes the widgets show. */
        internal val WIDGET_TABLES = arrayOf("calendars", "device_calendar_prefs", "events", "event_exceptions", "tasks")
        private const val UPDATE_DEBOUNCE_MILLIS = 500L
        private const val MIDNIGHT_DELAY_SECONDS = 5L
        private const val MIDNIGHT_WINDOW_MILLIS = 10 * 60 * 1000L
        private const val CHANGE_WINDOW_MILLIS = 60 * 1000L
        private const val MIDNIGHT_REQUEST = 0
        private const val CHANGE_REQUEST = 1

        private val widgets: List<GlanceAppWidget> get() = listOf(MonthWidget(), DayWidget(), AgendaWidget(), YearWidget(), TasksWidget())

        suspend fun updateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            for (widget in widgets) {
                if (manager.getGlanceIds(widget.javaClass).isNotEmpty()) widget.updateAll(context)
            }
        }

        /**
         * One alarm shortly after the next midnight, within ten minutes (a plain inexact alarm may
         * be put off for hours); it does not wake the device, which fires it as soon as the screen
         * is on. Runs on every widget render too, so a widget placed later still turns over.
         */
        suspend fun scheduleNextDay(context: Context) {
            if (!anyPlaced(context)) return
            DeviceCalendarRefreshWorker.enqueueIfNeeded(context)
            val time = context.widgetDeps().time()
            val next = time.today().plusDays(1).atStartOfDay(time.zone()).plusSeconds(MIDNIGHT_DELAY_SECONDS).toInstant()
            context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, next.toEpochMilli(), MIDNIGHT_WINDOW_MILLIS, refreshIntent(context, MIDNIGHT_REQUEST))
        }

        /**
         * One more refresh at [at], when the day widget's "now or next" card changes; it replaces
         * the previous one and, like the midnight alarm, does not wake the device. Exact where the
         * app may set exact alarms (from Android 12 on a window is stretched to ten minutes).
         */
        fun scheduleRefresh(context: Context, at: Instant) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val intent = refreshIntent(context, CHANGE_REQUEST)
            if (ReminderScheduler.canScheduleExact(alarms)) {
                alarms.setExact(AlarmManager.RTC, at.toEpochMilli(), intent)
            } else {
                alarms.setWindow(AlarmManager.RTC, at.toEpochMilli(), CHANGE_WINDOW_MILLIS, intent)
            }
        }

        internal suspend fun anyPlaced(context: Context): Boolean {
            val manager = GlanceAppWidgetManager(context)
            return widgets.any { manager.getGlanceIds(it.javaClass).isNotEmpty() }
        }

        private fun refreshIntent(context: Context, request: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Midnight, boot, clock, time zone and language changes: the widgets show another "today" or another language. */
@AndroidEntryPoint
class WidgetRefreshReceiver : InjectedReceiver() {
    private companion object {
        val HANDLED = setOf(
            WidgetUpdater.ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
        )
    }

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        scope.launch {
            try {
                WidgetUpdater.updateAll(context)
                WidgetUpdater.scheduleNextDay(context)
            } finally {
                pending.finish()
            }
        }
    }
}
