package com.celander.app.search

import com.celander.app.calendar.device.DeviceCalendarSource
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.TaskDao
import com.celander.app.core.database.toModel
import com.celander.app.core.device.TextMatch
import com.celander.app.core.di.DefaultDispatcher
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.time.TimeSource
import com.celander.app.event.zone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

enum class SearchRange { ALL, FUTURE, PAST }

/** No calendar ids means all visible calendars. */
data class SearchFilter(val range: SearchRange = SearchRange.ALL, val calendarIds: Set<Long> = emptySet())

sealed interface SearchHit {
    val key: String

    /** Day the hit is listed under; null for tasks without a due date. */
    val date: LocalDate?
    val sortMillis: Long
    val past: Boolean

    data class EventHit(
        val occurrence: EventOccurrence,
        override val date: LocalDate,
        override val sortMillis: Long,
        override val past: Boolean,
    ) : SearchHit {
        override val key get() = "e:${occurrence.key}"
    }

    data class TaskHit(
        val item: TaskItem,
        override val date: LocalDate?,
        override val sortMillis: Long,
        override val past: Boolean,
    ) : SearchHit {
        override val key get() = "t:${item.task.id}"
    }
}

/**
 * Full-text search over events and tasks of visible calendars. A series is listed once, at its next
 * matching occurrence (or its last one if it is over): a changed occurrence is found by its own
 * title, place and description, and one renamed away from the search does not stand for the series.
 * Runs off the main thread on the FTS index.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val db: CelanderDatabase,
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val exceptionDao: ExceptionDao,
    private val taskDao: TaskDao,
    private val time: TimeSource,
    private val device: DeviceCalendarSource,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    /** Results for [text] that update whenever events, tasks, calendars or the phone's calendars change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun results(text: String, filter: SearchFilter): Flow<List<SearchHit>> =
        combine(db.invalidationTracker.createFlow("events", "event_exceptions", "tasks", "calendars"), device.changes) { _, _ -> }
            .mapLatest { search(text, filter) }

    /** Main-safe: reading, matching changed occurrences and expanding series all run on [dispatcher]. */
    suspend fun search(text: String, filter: SearchFilter): List<SearchHit> = withContext(dispatcher) { find(text, filter) }

    private suspend fun find(text: String, filter: SearchFilter): List<SearchHit> {
        val match = FtsQuery.build(text) ?: return emptyList()
        val calendars = calendarDao.getAll().filter { it.visible }.map { it.toModel() }.associateBy { it.id }
        val allowed = { calendarId: Long -> calendarId in calendars && (filter.calendarIds.isEmpty() || calendarId in filter.calendarIds) }
        val matched = eventDao.search(match).map { it.toModel() }.filter { allowed(it.calendarId) }
        val matchedIds = matched.map { it.id }.toSet()
        val query = TextMatch.prepare(text)
        val events = matched + seriesWithMatchingExceptions(query, matchedIds).filter { allowed(it.calendarId) }
        val exceptions = events.filter { it.isRecurring }.associate { event ->
            event.id to exceptionDao.getForEvent(event.id).map { it.toModel(event.zone) }
        }
        val tasks = taskDao.search(match).map { it.toModel() }.filter { allowed(it.calendarId) }
        // The phone's calendars are searched in memory; if that fails, local hits still show.
        val deviceOccurrences = try {
            device.search(text, time.now(), time.zone()) { filter.calendarIds.isEmpty() || it in filter.calendarIds }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val zone = time.zone()
        val now = time.now()
        val today = now.atZone(zone).toLocalDate()
        val eventHits = events.mapNotNull { event ->
            val calendar = calendars.getValue(event.calendarId)
            val occurrence = if (event.isRecurring) {
                representative(event, exceptions[event.id].orEmpty(), calendar, today, now, zone, event.id in matchedIds, query)
            } else {
                OccurrenceCalculator.single(event, calendar)
            }
            occurrence?.let {
                SearchHit.EventHit(
                    occurrence = it,
                    date = it.timing.startDate(zone),
                    sortMillis = it.timing.startInstant(zone).toEpochMilli(),
                    past = !it.timing.endInstant(zone).isAfter(now),
                )
            }
        }
        val taskHits = tasks.map { task ->
            val due = task.dueDate
            SearchHit.TaskHit(
                item = TaskItem(task, calendars.getValue(task.calendarId)),
                date = due,
                sortMillis = due?.atTime(task.dueTime ?: LocalTime.MIN)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE,
                // Open overdue tasks still need doing, so only done tasks count as past.
                past = task.done,
            )
        }
        val deviceHits = deviceOccurrences.map { occurrence ->
            SearchHit.EventHit(
                occurrence = occurrence,
                date = occurrence.timing.startDate(zone),
                sortMillis = occurrence.timing.startInstant(zone).toEpochMilli(),
                past = !occurrence.timing.endInstant(zone).isAfter(now),
            )
        }
        return (eventHits + deviceHits + taskHits)
            .filter {
                when (filter.range) {
                    SearchRange.ALL -> true
                    SearchRange.FUTURE -> !it.past
                    SearchRange.PAST -> it.past
                }
            }
            .sortedWith(compareBy<SearchHit>({ it.sortMillis }, { it.key }))
            .take(MAX_RESULTS)
    }

    /**
     * Series not in [found] with a changed occurrence whose own title, place or description matches
     * [query]. Those texts are not in the FTS index, so they are matched here like the index would.
     */
    private suspend fun seriesWithMatchingExceptions(query: TextMatch.Query?, found: Set<Long>): List<Event> {
        if (query == null) return emptyList()
        val byEvent = exceptionDao.getWithOwnText().filter { it.eventId !in found }.groupBy { it.eventId }
        return byEvent.keys.toList().chunked(MAX_IDS_PER_QUERY)
            .flatMap { eventDao.getByIds(it) }
            .map { it.toModel() }
            .filter { event ->
                byEvent[event.id].orEmpty().any { x ->
                    query.matches(x.title ?: event.title, x.location ?: event.location, x.description ?: event.description)
                }
            }
    }

    /**
     * The occurrence a series is listed at: the next or ongoing matching one, else the last. Occurrences
     * with the series' texts match if the series did ([seriesMatches], from the FTS index); changed
     * ones with texts of their own are matched against [query], wherever they were moved to. Null if
     * nothing matches.
     */
    private fun representative(
        event: Event,
        exceptions: List<EventException>,
        calendar: CalendarInfo,
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
        seriesMatches: Boolean,
        query: TextMatch.Query?,
    ): EventOccurrence? {
        fun matches(o: EventOccurrence): Boolean =
            if (o.title == event.title && o.location == event.location && o.description == event.description) {
                seriesMatches
            } else {
                query?.matches(o.title, o.location, o.description) == true
            }
        val inWindow = OccurrenceCalculator.expand(
            listOf(event),
            mapOf(event.id to exceptions),
            mapOf(calendar.id to calendar),
            today.minusDays(LOOK_BACK_DAYS),
            today.plusDays(LOOK_AHEAD_DAYS),
            zone,
        )
        val changed = exceptions.filter { !it.cancelled }.map { OccurrenceCalculator.occurrence(event, calendar, it.originalStart, it) }
        val occurrences = (inWindow + changed).distinctBy { it.originalStart }
            .filter { matches(it) }
            .sortedBy { it.timing.startInstant(zone) }
        return occurrences.firstOrNull { it.timing.endInstant(zone).isAfter(now) }
            ?: occurrences.lastOrNull()
            ?: OccurrenceCalculator.single(event, calendar).takeIf { seriesMatches }
    }

    private companion object {
        const val MAX_RESULTS = 300

        /** Stays below SQLite's limit of bound variables in one statement. */
        const val MAX_IDS_PER_QUERY = 500
        const val LOOK_BACK_DAYS = 400L
        const val LOOK_AHEAD_DAYS = 800L
    }
}
