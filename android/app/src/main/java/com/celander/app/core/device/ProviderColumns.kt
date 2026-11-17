package com.celander.app.core.device

/**
 * Column names and values of Android's calendar provider (`CalendarContract`) that Celander writes,
 * as plain strings so the write planning stays testable without Android. A Robolectric test checks
 * them against `CalendarContract`.
 */
object ProviderColumns {
    // Events
    const val ID = "_id"
    const val CALENDAR_ID = "calendar_id"
    const val TITLE = "title"
    const val EVENT_LOCATION = "eventLocation"
    const val DESCRIPTION = "description"
    const val DTSTART = "dtstart"
    const val DTEND = "dtend"
    const val DURATION = "duration"
    const val EVENT_TIMEZONE = "eventTimezone"
    const val ALL_DAY = "allDay"
    const val RRULE = "rrule"
    const val EXDATE = "exdate"
    const val STATUS = "eventStatus"
    const val ORIGINAL_INSTANCE_TIME = "originalInstanceTime"
    const val ORIGINAL_ALL_DAY = "originalAllDay"

    // Reminders
    const val REMINDER_EVENT_ID = "event_id"
    const val REMINDER_MINUTES = "minutes"
    const val REMINDER_METHOD = "method"

    // Calendars
    const val MAX_REMINDERS = "maxReminders"
    const val ALLOWED_REMINDERS = "allowedReminders"

    /** `Events.STATUS_CANCELED`. */
    const val STATUS_CANCELED = 2

    /** `Reminders.METHOD_DEFAULT`, `METHOD_ALERT` and `METHOD_EMAIL`. */
    const val METHOD_DEFAULT = 0
    const val METHOD_ALERT = 1
    const val METHOD_EMAIL = 2

    /** All-day rows are stored in this zone, with midnight UTC boundaries. */
    const val UTC = "UTC"
}
