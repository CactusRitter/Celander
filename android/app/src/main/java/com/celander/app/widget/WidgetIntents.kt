package com.celander.app.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.celander.app.MainActivity
import com.celander.app.calendar.HomeDestination
import com.celander.app.core.model.EventOccurrence
import com.celander.app.notifications.DueReminder
import com.celander.app.notifications.ReminderKind
import com.celander.app.notifications.putReminder
import java.time.Instant
import java.time.LocalDate

/**
 * Intents that open Celander from a widget. Each one gets its own data URI, so the system keeps the
 * pending intents of the many tap targets apart.
 */
internal object WidgetIntents {
    fun view(context: Context, destination: HomeDestination, date: LocalDate?): Intent =
        base(context, MainActivity.ACTION_OPEN_VIEW, "view/${destination.name}/${date?.toEpochDay() ?: "-"}")
            .putExtra(MainActivity.EXTRA_DESTINATION, destination.name)
            .apply { if (date != null) putExtra(MainActivity.EXTRA_EPOCH_DAY, date.toEpochDay()) }

    fun event(context: Context, occurrence: EventOccurrence): Intent =
        base(context, MainActivity.ACTION_OPEN_ENTRY, "event/${occurrence.event.id}/${occurrence.originalStart}")
            .putReminder(DueReminder(occurrence.key, ReminderKind.EVENT, occurrence.event.id, occurrence.originalStart, Instant.EPOCH))

    fun task(context: Context, taskId: Long): Intent =
        base(context, MainActivity.ACTION_OPEN_ENTRY, "task/$taskId")
            .putReminder(DueReminder("task:$taskId", ReminderKind.TASK, taskId, 0, Instant.EPOCH))

    fun newEvent(context: Context, date: LocalDate): Intent =
        base(context, MainActivity.ACTION_NEW_ENTRY, "new/event/${date.toEpochDay()}")
            .putExtra(MainActivity.EXTRA_ENTRY_KIND, MainActivity.KIND_EVENT)
            .putExtra(MainActivity.EXTRA_EPOCH_DAY, date.toEpochDay())

    fun newTask(context: Context): Intent =
        base(context, MainActivity.ACTION_NEW_ENTRY, "new/task")
            .putExtra(MainActivity.EXTRA_ENTRY_KIND, MainActivity.KIND_TASK)

    private fun base(context: Context, action: String, path: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .setData("celander://widget/$path".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
