package com.celander.app.intake

import com.celander.app.core.ical.IcsMapper
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import java.time.ZoneId

/** What can be done with an incoming entry, given what is already there. */
enum class Verdict {
    NEW,

    /** It is there and the file changes it. */
    UPDATE,

    /** It is there like this already (for a cancellation: that occurrence is gone already). */
    UNCHANGED,

    /** The file is older (lower SEQUENCE) than what was taken over before. */
    OUTDATED,

    /** A cancellation of something that is there. */
    CANCEL,

    /** A cancellation of something that is not there. */
    NOT_FOUND,

    /** It is there, in a calendar Celander cannot change. */
    READ_ONLY,
}

/** The event already in the calendar for an incoming UID. [sequence] is known only if it came in this way before. */
data class ExistingEvent(
    val event: Event,
    val exceptions: List<EventException>,
    val writable: Boolean,
    val sequence: Int? = null,
    val calendarName: String = "",
)

data class EventChange(val event: Event, val exceptions: List<EventException>)

object IntakePlan {
    fun verdict(incoming: IncomingEvent, existing: ExistingEvent?): Verdict {
        if (existing == null) return if (incoming.cancel) Verdict.NOT_FOUND else Verdict.NEW
        if (existing.sequence != null && incoming.sequence < existing.sequence) return Verdict.OUTDATED
        val change = change(incoming, existing)
        val same = change != null && same(change, EventChange(existing.event, existing.exceptions))
        return when {
            same -> Verdict.UNCHANGED
            !existing.writable -> Verdict.READ_ONLY
            incoming.cancel -> Verdict.CANCEL
            else -> Verdict.UPDATE
        }
    }

    /** A cancellation of the whole event, as against one of single occurrences of a series. */
    fun deletesWholeEvent(incoming: IncomingEvent, existing: ExistingEvent): Boolean =
        incoming.cancel && (incoming.master != null || !existing.event.isRecurring)

    /**
     * [existing] as the file wants it: title, place, notes, time and repetition from the file; the
     * calendar, color and reminders stay the user's. Null if the event goes away altogether.
     */
    fun change(incoming: IncomingEvent, existing: ExistingEvent): EventChange? {
        if (deletesWholeEvent(incoming, existing)) return null
        val old = existing.event
        val master = incoming.master
        if (master != null) {
            val (parsed, exceptions) = IcsMapper.fromIcal(listOf(master) + incoming.overrides, old.calendarId).first()
            val event = old.copy(
                title = parsed.title,
                location = parsed.location,
                description = parsed.description,
                timing = parsed.timing,
                rrule = parsed.rrule,
            )
            // As long as the series runs as before, what was changed for single occurrences here stays.
            val unchangedSeries = event.rrule != null && event.rrule == old.rrule && sameTiming(event.timing, old.timing)
            val replaced = exceptions.map { it.originalStart }.toSet()
            val kept = if (unchangedSeries) existing.exceptions.filter { it.originalStart !in replaced } else emptyList()
            return EventChange(event, kept + exceptions.map { it.copy(eventId = old.id) })
        }
        // Only single occurrences: changes to a series that is there, or to the one date taken over alone.
        if (!old.isRecurring) {
            val single = incoming.overrides.first().copy(recurrenceId = null, cancelled = false)
            val parsed = IcsMapper.fromIcal(listOf(single), old.calendarId).first().first
            return EventChange(
                old.copy(title = parsed.title, location = parsed.location, description = parsed.description, timing = parsed.timing),
                emptyList(),
            )
        }
        val series = IcsMapper.toIcal(old, emptyList()).first()
        val added = incoming.overrides.mapNotNull { override ->
            val component = if (incoming.cancel) override.copy(cancelled = true) else override
            IcsMapper.fromIcal(listOf(series, component), old.calendarId).firstOrNull()?.second?.singleOrNull()?.copy(eventId = old.id)
        }
        val replaced = added.map { it.originalStart }.toSet()
        return EventChange(old, existing.exceptions.filter { it.originalStart !in replaced } + added)
    }

    private fun same(a: EventChange, b: EventChange): Boolean {
        fun clean(text: String?) = text?.replace("\r\n", "\n")?.trim()
        fun key(x: EventException) = if (x.cancelled) {
            listOf(x.originalStart, true)
        } else {
            listOf(x.originalStart, false, clean(x.title), clean(x.location), clean(x.description), x.timing?.let(::timingKey))
        }
        return clean(a.event.title) == clean(b.event.title) &&
            clean(a.event.location) == clean(b.event.location) &&
            clean(a.event.description) == clean(b.event.description) &&
            a.event.rrule == b.event.rrule &&
            sameTiming(a.event.timing, b.event.timing) &&
            a.exceptions.map(::key).toSet() == b.exceptions.map(::key).toSet()
    }

    private fun timingKey(timing: EventTiming): Pair<Long, Long> = when (timing) {
        is EventTiming.Timed -> timing.start.toEpochMilli() to timing.end.toEpochMilli()
        is EventTiming.AllDay -> timing.startDate.toEpochDay() to timing.endDate.toEpochDay()
    }

    /** The same moments; the zone may be written differently after a round through a server. */
    private fun sameTiming(a: EventTiming, b: EventTiming) = a.javaClass == b.javaClass && timingKey(a) == timingKey(b)

    /**
     * What else happens while [timing] does, among [occurrences] of the shown calendars. For a timed
     * entry only timed ones that overlap (all-day ones would drown the list in birthdays and
     * holidays); for an all-day entry everything on its days.
     */
    fun conflicts(timing: EventTiming, occurrences: List<EventOccurrence>, ownEventId: Long?, zone: ZoneId): List<EventOccurrence> {
        val start = timing.startInstant(zone)
        val end = timing.endInstant(zone)
        return occurrences.filter { other ->
            other.event.id != ownEventId &&
                (timing is EventTiming.AllDay || other.timing is EventTiming.Timed) &&
                other.timing.startInstant(zone) < end && other.timing.endInstant(zone) > start
        }.sortedBy { it.timing.startInstant(zone) }
    }
}
