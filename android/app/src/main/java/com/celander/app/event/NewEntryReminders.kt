package com.celander.app.event

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.settings.AppSettings

/** The app-wide default reminders from Settings › Reminders. */
val AppSettings.reminderDefaults: ReminderDefaults get() = ReminderDefaults(eventReminders, allDayReminders, taskReminders)

/** Which reminders a new entry starts with: the calendar's own defaults, else the app-wide ones. */
object NewEntryReminders {
    /** The phone's calendars cannot store reminders on the day of an all-day event, and cap their number. */
    fun fit(calendar: CalendarInfo?, minutes: List<Int>): List<Int> =
        if (calendar?.source == CalendarSource.DEVICE) {
            minutes.filter { it >= 0 }.take(calendar.maxReminders?.coerceIn(1, MAX_REMINDERS) ?: MAX_REMINDERS)
        } else {
            minutes
        }

    /** The defaults [calendar] uses (its own or [global]), each list as the calendar can store it. */
    fun effective(calendar: CalendarInfo?, global: ReminderDefaults): ReminderDefaults {
        val defaults = ReminderDefaults.effective(calendar, global)
        return ReminderDefaults(fit(calendar, defaults.event), fit(calendar, defaults.allDay), fit(calendar, defaults.task))
    }

    /** Reminders for a new entry of [kind] in [calendar]. */
    fun forEntry(calendar: CalendarInfo?, global: ReminderDefaults, kind: EntryKind, allDay: Boolean): List<Int> =
        effective(calendar, global).forEntry(kind, allDay)
}
