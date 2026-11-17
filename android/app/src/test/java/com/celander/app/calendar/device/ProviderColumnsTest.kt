package com.celander.app.calendar.device

import android.provider.CalendarContract
import com.celander.app.core.device.ProviderColumns
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The pure write planner names columns as strings; they must be the provider's. */
@RunWith(RobolectricTestRunner::class)
class ProviderColumnsTest {
    @Test
    fun columnsMatchCalendarContract() {
        val expected = mapOf(
            ProviderColumns.ID to CalendarContract.Events._ID,
            ProviderColumns.CALENDAR_ID to CalendarContract.Events.CALENDAR_ID,
            ProviderColumns.TITLE to CalendarContract.Events.TITLE,
            ProviderColumns.EVENT_LOCATION to CalendarContract.Events.EVENT_LOCATION,
            ProviderColumns.DESCRIPTION to CalendarContract.Events.DESCRIPTION,
            ProviderColumns.DTSTART to CalendarContract.Events.DTSTART,
            ProviderColumns.DTEND to CalendarContract.Events.DTEND,
            ProviderColumns.DURATION to CalendarContract.Events.DURATION,
            ProviderColumns.EVENT_TIMEZONE to CalendarContract.Events.EVENT_TIMEZONE,
            ProviderColumns.ALL_DAY to CalendarContract.Events.ALL_DAY,
            ProviderColumns.RRULE to CalendarContract.Events.RRULE,
            ProviderColumns.EXDATE to CalendarContract.Events.EXDATE,
            ProviderColumns.STATUS to CalendarContract.Events.STATUS,
            ProviderColumns.ORIGINAL_INSTANCE_TIME to CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            ProviderColumns.ORIGINAL_ALL_DAY to CalendarContract.Events.ORIGINAL_ALL_DAY,
            ProviderColumns.REMINDER_EVENT_ID to CalendarContract.Reminders.EVENT_ID,
            ProviderColumns.REMINDER_MINUTES to CalendarContract.Reminders.MINUTES,
            ProviderColumns.REMINDER_METHOD to CalendarContract.Reminders.METHOD,
            ProviderColumns.MAX_REMINDERS to CalendarContract.Calendars.MAX_REMINDERS,
            ProviderColumns.ALLOWED_REMINDERS to CalendarContract.Calendars.ALLOWED_REMINDERS,
        )
        expected.forEach { (ours, android) -> assertEquals(android, ours) }
        assertEquals(CalendarContract.Events.STATUS_CANCELED, ProviderColumns.STATUS_CANCELED)
        assertEquals(CalendarContract.Reminders.METHOD_DEFAULT, ProviderColumns.METHOD_DEFAULT)
        assertEquals(CalendarContract.Reminders.METHOD_ALERT, ProviderColumns.METHOD_ALERT)
        assertEquals(CalendarContract.Reminders.METHOD_EMAIL, ProviderColumns.METHOD_EMAIL)
    }
}
