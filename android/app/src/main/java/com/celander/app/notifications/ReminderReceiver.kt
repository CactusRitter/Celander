package com.celander.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.CallSuper
import com.celander.app.core.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Gives Hilt's generated receiver an `onReceive` to inject in before ours runs. */
abstract class InjectedReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Alarm, notification actions and system events (boot, clock, time zone, update) that affect reminders. */
@AndroidEntryPoint
class ReminderReceiver : InjectedReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> scheduler.deliverDue()
                    ACTION_SNOOZE -> intent.reminder()?.let { scheduler.snooze(it) }
                    ACTION_DONE -> intent.reminder()?.let { scheduler.completeTask(it) }
                    else -> scheduler.reschedule()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.celander.app.action.REMINDER_FIRE"
        const val ACTION_SNOOZE = "com.celander.app.action.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.celander.app.action.REMINDER_DONE"
    }
}
