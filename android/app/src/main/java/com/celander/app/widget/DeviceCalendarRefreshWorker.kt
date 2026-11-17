package com.celander.app.widget

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.Duration

/**
 * Refreshes the widgets when the phone's calendars change while Celander is not running (a Google
 * sync, an edit in the calendar app). Waits on a content URI trigger, then queues itself again;
 * only while the phone's calendars are shown and a widget is placed.
 */
class DeviceCalendarRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.updateAll(applicationContext)
        // Runs after this one finishes; replacing would cancel the running work.
        enqueueIfNeeded(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "device-calendar-widgets"
        private val SETTLE_DELAY: Duration = Duration.ofSeconds(2)

        suspend fun enqueueIfNeeded(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val work = WorkManager.getInstance(context)
            val readable = context.widgetDeps().device().status.first().readable
            if (!readable || !WidgetUpdater.anyPlaced(context)) {
                work.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(SETTLE_DELAY)
                .build()
            work.enqueueUniqueWork(NAME, policy, OneTimeWorkRequestBuilder<DeviceCalendarRefreshWorker>().setConstraints(constraints).build())
        }
    }
}
