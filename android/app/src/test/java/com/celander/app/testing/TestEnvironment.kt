package com.celander.app.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.calendar.data.SampleDataRepository
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.event.EventRepository
import com.celander.app.settings.SettingsRepository
import com.celander.app.task.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File
import java.time.LocalDateTime

/** Real repositories on an in-memory database and a temporary settings file (Robolectric). */
class TestEnvironment(
    directory: File,
) : Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val time = FixedTimeSource(LocalDateTime.of(2026, 10, 23, 10, 10).atZone(BERLIN).toInstant())
    val db: CelanderDatabase = Room.inMemoryDatabaseBuilder(context, CelanderDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") })
    val calendars = CalendarsRepository(db, db.calendarDao(), db.eventDao(), db.exceptionDao(), db.taskDao(), settings, time)
    val events = EventRepository(db, db.eventDao(), db.exceptionDao(), time)
    val tasks = TaskRepository(db, db.taskDao(), db.googleTasksDao(), time)
    val snackbars = SnackbarManager(scope)
    val samples = SampleDataRepository(context, db, db.calendarDao(), db.eventDao(), db.taskDao(), calendars, events, tasks, settings, time)

    override fun close() {
        db.close()
        scope.cancel()
    }
}
