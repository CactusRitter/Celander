package com.celander.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.di.ApplicationScope
import com.celander.app.notifications.ReminderNotifier
import com.celander.app.notifications.ReminderScheduler
import com.celander.app.task.google.GoogleTasksSyncTrigger
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

    @Inject lateinit var push: com.celander.app.push.PushManager

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    /** New versions published on GitHub (Settings → Updates and the notice at the top of the calendar). */
    val updates: com.ce.kit.update.Updates get() = com.ce.kit.CeKit.updates

    override fun onCreate() {
        super.onCreate()
        com.ce.kit.CeKit.init(this, com.ce.kit.CeAppConfig("Celander", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, "CactusRitter/Celander"))
        notifier.ensureChannels()
        // The first calendar is named in the language of the first start.
        scope.launch { calendars.ensureDefault(getString(R.string.default_calendar_name)) }
        reminders.start(scope)
        widgets.start(scope)
        // Removes Google lists restored from a backup without their account, then syncs on changes.
        googleTasks.start(scope)
        // Calendars of the connected accounts: sync at start and every quarter of an hour.
        accounts.start(scope)
        // Instant calendar updates through Firebase and the relay, when the keys are set (otherwise the polling above).
        push.start(scope)
        // While the app is on screen: look for changes at the accounts every minute.
        registerActivityLifecycleCallbacks(VisibleTracker { visible -> accounts.onVisibilityChanged(scope, visible) })
    }
}

/** Tells [onChange] when the app goes on screen (the first activity starts) and off it (the last one stops). */
private class VisibleTracker(private val onChange: (Boolean) -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) onChange(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started == 0) onChange(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
