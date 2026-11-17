package com.celander.app

import android.app.Application
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.di.ApplicationScope
import com.celander.app.notifications.ReminderNotifier
import com.celander.app.notifications.ReminderScheduler
import com.celander.app.task.google.GoogleTasksSyncTrigger
import com.celander.app.update.Updates
import com.celander.app.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class CelanderApp : Application() {
    @Inject lateinit var calendars: CalendarsRepository

    @Inject lateinit var notifier: ReminderNotifier

    @Inject lateinit var reminders: ReminderScheduler

    @Inject lateinit var widgets: WidgetUpdater

    @Inject lateinit var googleTasks: GoogleTasksSyncTrigger

    @Inject lateinit var accounts: com.celander.app.account.AccountRepository

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    /** New versions published on GitHub (Settings → Updates and the notice at the top of the calendar). */
    val updates: Updates by lazy { Updates(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        // The first calendar is named in the language of the first start.
        scope.launch { calendars.ensureDefault(getString(R.string.default_calendar_name)) }
        reminders.start(scope)
        widgets.start(scope)
        // Removes Google lists restored from a backup without their account, then syncs on changes.
        googleTasks.start(scope)
        // Calendars of the connected accounts: sync at start and every quarter of an hour.
        accounts.start(scope)
    }
}
