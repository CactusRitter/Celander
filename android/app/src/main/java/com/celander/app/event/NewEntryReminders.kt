package com.celander.app.event

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.settings.AppSettings

/** The app-wide default reminders from Settings › Reminders. */
val AppSettings.reminderDefaults: ReminderDefaults get() = ReminderDefaults(eventReminders, allDayReminders, taskReminders)

/** Which reminders a new entry starts with: the calendar's own defaults, else the app-wide ones. */
object NewEntryReminders {
    /** The defaults [calendar] uses (its own or [global]). */
    fun effective(calendar: CalendarInfo?, global: ReminderDefaults): ReminderDefaults = ReminderDefaults.effective(calendar, global)

    /** Reminders for a new entry of [kind] in [calendar]. */
    fun forEntry(calendar: CalendarInfo?, global: ReminderDefaults, kind: EntryKind, allDay: Boolean): List<Int> =
        effective(calendar, global).forEntry(kind, allDay)
}
