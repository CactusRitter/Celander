package com.celander.app.settings

import androidx.room.withTransaction
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.SnoozeDao
import com.celander.app.core.model.CalendarSource
import com.celander.app.task.google.GoogleTasksConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Delete all data": Google Tasks is disconnected (only locally, nothing is deleted at Google), then
 * every local calendar goes with its entries and pending snoozes; the app starts over with one
 * calendar. Celander's settings for the phone's calendars stay.
 */
@Singleton
class DataReset @Inject constructor(
    private val db: CelanderDatabase,
    private val calendarDao: CalendarDao,
    private val snoozeDao: SnoozeDao,
    private val calendars: CalendarsRepository,
    private val settings: SettingsRepository,
    private val googleTasks: GoogleTasksConnection,
) {
    /** Appearance and other preferences stay; only data and the calendar choices go. */
    suspend fun deleteAll(defaultCalendarName: String) {
        googleTasks.disconnectLocally()
        db.withTransaction {
            calendarDao.deleteBySource(CalendarSource.LOCAL.name)
            snoozeDao.deleteAll()
        }
        settings.update {
            it.copy(defaultCalendarId = null, lastUsedCalendarId = null, defaultTaskCalendarId = null, lastUsedTaskCalendarId = null)
        }
        calendars.ensureDefault(defaultCalendarName)
    }
}
