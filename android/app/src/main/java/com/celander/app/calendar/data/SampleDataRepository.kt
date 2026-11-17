package com.celander.app.calendar.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.room.withTransaction
import com.celander.app.R
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.TaskDao
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.Task
import com.celander.app.core.time.TimeSource
import com.celander.app.designsystem.CalendarColor
import com.celander.app.event.EventRepository
import com.celander.app.settings.SettingsRepository
import com.celander.app.task.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Example calendars, events and tasks around today, in the app language, to try the app without
 * typing. Everything is flagged as sample data and can be removed in one go.
 */
@Singleton
class SampleDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CelanderDatabase,
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val taskDao: TaskDao,
    private val calendars: CalendarsRepository,
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    val hasSampleData: Flow<Boolean> = calendarDao.observeSampleCount().map { it > 0 }

    suspend fun load() {
        val today = time.today()
        val zone = time.zone()
        val existing = calendars.first().filter { it.source == CalendarSource.LOCAL }
        val personal = existing.firstOrNull { it.id == settings.current().defaultCalendarId }?.id ?: existing.first().id
        db.withTransaction {
            val work = calendars.create(text(R.string.sample_calendar_work), CalendarColor.BLUE.key, isSample = true)
            val family = calendars.create(text(R.string.sample_calendar_family), CalendarColor.CORAL.key, isSample = true)
            val sport = calendars.create(text(R.string.sample_calendar_sport), CalendarColor.GREEN.key, isSample = true)

            fun timed(calendarId: Long, @StringRes title: Int, day: LocalDate, start: LocalTime, minutes: Long, location: String = "", rrule: String? = null) =
                Event(
                    uid = UUID.randomUUID().toString(),
                    calendarId = calendarId,
                    title = text(title),
                    location = location,
                    timing = EventTiming.Timed(
                        day.atTime(start).atZone(zone).toInstant(),
                        day.atTime(start).plusMinutes(minutes).atZone(zone).toInstant(),
                        zone,
                    ),
                    rrule = rrule,
                    reminders = listOf(10),
                    isSample = true,
                )

            fun allDay(calendarId: Long, @StringRes title: Int, first: LocalDate, last: LocalDate = first, rrule: String? = null) =
                Event(
                    uid = UUID.randomUUID().toString(),
                    calendarId = calendarId,
                    title = text(title),
                    timing = EventTiming.AllDay(first, last),
                    rrule = rrule,
                    reminders = listOf(360),
                    isSample = true,
                )

            val weekdays = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
            listOf(
                timed(work, R.string.sample_standup, today.minusDays(14), LocalTime.of(9, 30), 15, rrule = weekdays),
                timed(work, R.string.sample_design_review, today, LocalTime.of(14, 0), 90, text(R.string.sample_room)),
                timed(personal, R.string.sample_dentist, today, LocalTime.of(15, 0), 45),
                timed(personal, R.string.sample_lunch, today.plusDays(1), LocalTime.of(12, 30), 60, text(R.string.sample_cafe)),
                timed(work, R.string.sample_kickoff, today.minusDays(2), LocalTime.of(10, 0), 60),
                timed(sport, R.string.sample_running, today.minusDays(6), LocalTime.of(18, 30), 75, rrule = "FREQ=WEEKLY"),
                timed(personal, R.string.sample_concert, today.plusDays(9), LocalTime.of(20, 0), 150, text(R.string.sample_venue)),
                timed(family, R.string.sample_dinner, today.plusDays(4), LocalTime.of(19, 0), 120),
                allDay(family, R.string.sample_birthday, today.plusDays(3), rrule = "FREQ=YEARLY"),
                allDay(personal, R.string.sample_trip, today.plusDays(6), today.plusDays(8)),
            ).forEach { events.create(it) }

            listOf(
                Task(calendarId = personal, title = text(R.string.sample_task_groceries), dueDate = today, dueTime = LocalTime.of(18, 0), isSample = true),
                Task(calendarId = work, title = text(R.string.sample_task_report), dueDate = today.plusDays(1), isSample = true),
                Task(calendarId = personal, title = text(R.string.sample_task_plants), dueDate = today.minusDays(1), isSample = true),
                Task(calendarId = family, title = text(R.string.sample_task_gift), dueDate = today.plusDays(2), isSample = true),
                Task(calendarId = personal, title = text(R.string.sample_task_flights), isSample = true),
                Task(calendarId = personal, title = text(R.string.sample_task_dentist), dueDate = today, dueTime = LocalTime.of(10, 0), isSample = true),
                Task(calendarId = personal, title = text(R.string.sample_task_bill), dueDate = today.plusDays(3), isSample = true),
                Task(calendarId = family, title = text(R.string.sample_task_library), dueDate = today.minusDays(3), isSample = true),
                Task(calendarId = family, title = text(R.string.sample_task_clean), dueDate = today.plusDays(6), dueTime = LocalTime.of(11, 0), isSample = true),
                Task(calendarId = personal, title = text(R.string.sample_task_ideas), isSample = true),
            ).forEach { tasks.create(it) }
        }
    }

    /** Removes sample calendars with their entries and sample entries moved to other calendars. */
    suspend fun clear() = db.withTransaction {
        eventDao.deleteSamples()
        taskDao.deleteSamples()
        calendarDao.deleteSamples()
    }

    private fun text(@StringRes id: Int): String = context.getString(id)
}
