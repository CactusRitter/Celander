package com.celander.app.calendar.data

import com.celander.app.calendar.CalendarWindow
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.TaskDao
import com.celander.app.core.database.toModel
import com.celander.app.core.di.DefaultDispatcher
import com.celander.app.core.model.TaskItem
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.event.zone
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/** Builds the visible content (occurrences and open tasks) of a date window, off the main thread. */
@Singleton
class CalendarContentRepository @Inject constructor(
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val exceptionDao: ExceptionDao,
    private val taskDao: TaskDao,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    fun window(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Flow<CalendarWindow> {
        // Widen by a day so events in other time zones or across midnight are not missed.
        val startUtc = start.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endUtc = endExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val firstDay = start.toEpochDay() - 1
        val lastDay = endExclusive.toEpochDay()
        return combine(
            calendarDao.observeAll(),
            eventDao.observeCandidates(startUtc, endUtc, firstDay, lastDay, includeHidden = false),
            exceptionDao.observeAll(),
            taskDao.observeOpenDue(start.toEpochDay(), endExclusive.toEpochDay() - 1, includeHidden = false),
        ) { calendars, eventEntities, exceptionEntities, taskEntities ->
            val visible = calendars.filter { it.visible && it.enabled }.associate { it.id to it.toModel() }
            val events = eventEntities.map { it.toModel() }
            val zones = events.associate { it.id to it.zone }
            val exceptions = exceptionEntities
                .filter { it.eventId in zones }
                .groupBy({ it.eventId }, { it.toModel(zones.getValue(it.eventId)) })
            val occurrences = OccurrenceCalculator.expand(events, exceptions, visible, start, endExclusive, zone)
            val tasks = taskEntities.mapNotNull { t -> visible[t.calendarId]?.let { TaskItem(t.toModel(), it) } }
            CalendarWindow(start, endExclusive, occurrences, tasks, zone)
        }.flowOn(dispatcher)
    }

    /** False until the first event or task exists; drives the welcome state. */
    val hasEntries: Flow<Boolean> = combine(eventDao.observeCount(), taskDao.observeCount()) { events, tasks -> events + tasks > 0 }
}
