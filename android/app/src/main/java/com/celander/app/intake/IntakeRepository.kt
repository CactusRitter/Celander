package com.celander.app.intake

import android.content.Context
import com.celander.app.calendar.data.CalendarContentRepository
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.database.EventDao
import com.celander.app.core.ical.IcsMapper
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.event.EventRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Finds what is already in the calendar for an incoming entry and applies what the user confirmed. */
@Singleton
class IntakeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val events: EventRepository,
    private val eventDao: EventDao,
    private val calendars: CalendarsRepository,
    private val content: CalendarContentRepository,
) {
    /**
     * Which row an imported UID became and with which SEQUENCE: accounts give an event their own UID
     * at the first sync, and the events table has no sequence.
     */
    private val imports = context.getSharedPreferences("ics_imports", Context.MODE_PRIVATE)

    suspend fun find(incoming: IncomingEvent): ExistingEvent? =
        find(incoming.uid) ?: incoming.orphanUid?.let { find(it) }

    private suspend fun find(uid: String): ExistingEvent? {
        val list = calendars.first()
        fun calendar(calendarId: Long) = list.firstOrNull { it.id == calendarId }
        fun writable(calendarId: Long) = calendar(calendarId)?.writable == true
        // "id:createdAt:sequence"; the creation time guards against the id naming another event after a restore.
        val noted = imports.getString(uid, null)?.split(':')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 }
        val remembered = noted?.let { (id, createdAt, _) -> events.get(id)?.takeIf { it.createdAt == createdAt } }
        val event = remembered
            ?: eventDao.getByUid(uid).sortedByDescending { writable(it.calendarId) }.firstNotNullOfOrNull { events.get(it.id) }
            ?: return null
        val loaded = events.getWithExceptions(event.id) ?: return null
        return ExistingEvent(
            loaded.event,
            loaded.exceptions,
            writable(event.calendarId),
            sequence = noted?.get(2)?.toInt(),
            calendarName = calendar(event.calendarId)?.name.orEmpty(),
        )
    }

    /** Adds a new entry to [calendarId]. */
    suspend fun add(incoming: IncomingEvent, calendarId: Long, reminders: List<Int>) {
        val components = listOfNotNull(incoming.master) + incoming.overrides.filter { !it.cancelled || incoming.master != null }
        IcsMapper.fromIcal(components, calendarId).forEach { (event, exceptions) ->
            val id = events.create(event.copy(reminders = reminders))
            val created = events.get(id) ?: return@forEach
            if (exceptions.isNotEmpty()) events.replace(created, exceptions)
            remember(event.uid, id, created.createdAt, incoming.sequence)
        }
    }

    /** Updates or (for a cancellation) deletes the existing event. */
    suspend fun apply(incoming: IncomingEvent, existing: ExistingEvent) {
        val change = IntakePlan.change(incoming, existing)
        if (change == null) {
            events.delete(existing.event.id)
            return
        }
        events.replace(change.event, change.exceptions)
        val uid = incoming.orphanUid?.takeIf { it == existing.event.uid } ?: incoming.uid
        remember(uid, existing.event.id, existing.event.createdAt, incoming.sequence)
    }

    private fun remember(uid: String, id: Long, createdAt: Long, sequence: Int) {
        imports.edit().putString(uid, "$id:$createdAt:$sequence").apply()
    }

    /** What else is on while [timing] is; for long entries only their first month is looked at. */
    suspend fun conflicts(timing: EventTiming, ownEventId: Long?, zone: ZoneId): List<EventOccurrence> {
        val first = timing.startDate(zone)
        val last = minOf(timing.lastDate(zone), first.plusDays(30))
        val window = content.window(first, last.plusDays(1), zone).first()
        return IntakePlan.conflicts(timing, window.occurrences, ownEventId, zone)
    }
}
