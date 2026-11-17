package com.celander.app.core.recurrence

import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Which occurrences of a series an edit or deletion applies to. */
enum class SeriesScope { THIS, THIS_AND_FOLLOWING, ALL }

/** Pure transformations of series for scoped edits and deletions. */
object SeriesEdits {
    private val UNTIL_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val UNTIL_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /**
     * The series ending right before the occurrence [key], or null when that occurrence is the first
     * one (then nothing of the series remains). COUNT is replaced by an equivalent UNTIL.
     */
    fun endBefore(event: Event, key: Long): Event? {
        val rule = RRule.parse(event.rrule ?: return null) ?: return null
        if (key <= event.timing.occurrenceKey) return null
        val until = when (event.timing) {
            is EventTiming.AllDay -> LocalDate.ofEpochDay(key - 1).format(UNTIL_DATE)
            is EventTiming.Timed -> Instant.ofEpochMilli(key - 1_000).atZone(ZoneOffset.UTC).format(UNTIL_UTC)
        }
        return event.copy(rrule = rule.copy(count = null, until = until).format())
    }

    /** Exceptions that still belong to a series ending before [key]. */
    fun exceptionsBefore(exceptions: List<EventException>, key: Long): List<EventException> =
        exceptions.filter { it.originalStart < key }

    /** Exceptions that move to a new series starting at [key], re-keyed by [shift] (new minus old start). */
    fun exceptionsFrom(exceptions: List<EventException>, key: Long, shift: Long): List<EventException> =
        exceptions.filter { it.originalStart >= key }.map { it.copy(id = 0, originalStart = it.originalStart + shift) }

    /** Marks a single occurrence as removed. */
    fun cancel(eventId: Long, key: Long): EventException = EventException(eventId = eventId, originalStart = key, cancelled = true)

    /**
     * Applies the change that turned [from] into [to] onto [target]: the same shift on the wall
     * clock (so 9:00 stays 9:00 across daylight saving), plus [to]'s duration, kind and zone.
     */
    fun shift(target: EventTiming, from: EventTiming, to: EventTiming): EventTiming {
        val fromZone = (from as? EventTiming.Timed)?.zone ?: ZoneOffset.UTC
        return when (to) {
            is EventTiming.Timed -> {
                val duration = Duration.between(to.start, to.end)
                val start = when (target) {
                    is EventTiming.Timed -> {
                        val delta = Duration.between(from.startLocal(), to.start.atZone(to.zone).toLocalDateTime())
                        target.start.atZone(target.zone).toLocalDateTime().plus(delta).atZone(to.zone).toInstant()
                    }
                    is EventTiming.AllDay -> {
                        val days = ChronoUnit.DAYS.between(from.startDate(fromZone), to.start.atZone(to.zone).toLocalDate())
                        target.startDate.plusDays(days).atTime(to.start.atZone(to.zone).toLocalTime()).atZone(to.zone).toInstant()
                    }
                }
                EventTiming.Timed(start, start.plus(duration), to.zone)
            }
            is EventTiming.AllDay -> {
                val days = ChronoUnit.DAYS.between(from.startDate(fromZone), to.startDate)
                val start = target.startDate((target as? EventTiming.Timed)?.zone ?: ZoneOffset.UTC).plusDays(days)
                EventTiming.AllDay(start, start.plusDays(ChronoUnit.DAYS.between(to.startDate, to.endDate)))
            }
        }
    }

    private fun EventTiming.startLocal(): LocalDateTime = when (this) {
        is EventTiming.Timed -> start.atZone(zone).toLocalDateTime()
        is EventTiming.AllDay -> startDate.atStartOfDay()
    }

    /**
     * "This only": what of [edited] differs from the series at [key] becomes the exception. Fields
     * that match the series stay empty, so later changes to the series still reach the occurrence.
     * Null when nothing differs any more.
     */
    fun exceptionAt(master: Event, key: Long, edited: Event): EventException? = EventException(
        eventId = master.id,
        originalStart = key,
        title = edited.title.takeIf { it != master.title },
        location = edited.location.takeIf { it != master.location },
        description = edited.description.takeIf { it != master.description },
        timing = edited.timing.takeIf { it != OccurrenceCalculator.timingAt(master.timing, key) },
    ).takeIf { it.overridesSomething }

    /**
     * Where an edit of the occurrence at [key] counts from: the time the editor showed (an earlier
     * change of the occurrence), unless that change made it all-day in a timed series or the other
     * way round. Such a time says nothing about the series' time of day.
     */
    fun shiftReference(master: Event, key: Long, exceptions: List<EventException>): EventTiming {
        val before = OccurrenceCalculator.timingAt(master.timing, key)
        val shown = changedAt(exceptions, key)?.timing ?: return before
        return if (shown::class == before::class) shown else before
    }

    /**
     * Whether the rule of [edited], the occurrence as edited, is still the one of [master]. A weekly
     * rule on just the start's own weekday is the plain weekly rule, so a rule rewritten to follow a
     * moved start still counts as the same.
     */
    fun sameRule(master: Event, edited: Event): Boolean {
        if (master.rrule == edited.rrule) return true
        val rule = normalizedRule(master) ?: return false
        return rule == normalizedRule(edited)
    }

    /**
     * "All events": the occurrence at [key] was edited into [edited]. A changed time moves the
     * series by as much as the occurrence was moved from where the editor showed it; then only
     * cancelled single occurrences survive, moved along. Text applies to the series only where it
     * was edited, not where the occurrence showed its own earlier change.
     */
    fun applyToAll(master: Event, key: Long, edited: Event, exceptions: List<EventException>): Pair<Event, List<EventException>> {
        val atKey = changedAt(exceptions, key)
        val from = shiftReference(master, key, exceptions)
        val text = TextEdits(master, atKey, edited, from)
        val timeKept = edited.timing == from
        val timing = if (timeKept) master.timing else shift(master.timing, from, edited.timing)
        val applied = text.applyTo(edited.copy(id = master.id, uid = master.uid, timing = timing, isSample = master.isSample, createdAt = master.createdAt), master)
        val sameRule = sameRule(master, edited)
        val updated = if (sameRule) applied else startOnRule(applied)
        val kept = if (timeKept) {
            exceptions.mapNotNull { if (it === atKey) text.clear(it) else it }
        } else {
            exceptions.filter { it.cancelled }.map { exception ->
                exception.copy(id = 0, originalStart = shift(OccurrenceCalculator.timingAt(master.timing, exception.originalStart), from, edited.timing).occurrenceKey)
            }
        }
        return updated to if (sameRule) kept else stillInSeries(updated, kept)
    }

    /** Result of "this and following": [master] ends before the split, [series] continues from it. */
    data class Split(
        val master: Event,
        val masterExceptions: List<EventException>,
        val series: Event,
        val seriesExceptions: List<EventException>,
    )

    /**
     * "This and following": the series ends before [key] and a new series starts with [edited]. A
     * COUNT is divided between both parts ([occurrencesBefore] stay with the original). Returns null
     * when [key] is the first occurrence; then the edit applies to the whole series.
     */
    fun splitAt(master: Event, key: Long, edited: Event, exceptions: List<EventException>, occurrencesBefore: Int, newUid: String): Split? {
        val shortened = endBefore(master, key) ?: return null
        val before = OccurrenceCalculator.timingAt(master.timing, key)
        val atKey = changedAt(exceptions, key)
        val from = shiftReference(master, key, exceptions)
        val text = TextEdits(master, atKey, edited, from)
        val timeKept = edited.timing == from
        val sameRule = sameRule(master, edited)
        val rule = edited.rrule?.let { RRule.parse(it) }
        val seriesRule = if (sameRule && rule?.count != null) rule.copy(count = maxOf(1, rule.count - occurrencesBefore)).format() else edited.rrule
        // The new series starts at the series' own time at [key], moved as far as the occurrence was
        // moved from where the editor showed it; an occurrence moved earlier keeps its own time.
        val seriesTiming = if (timeKept) before else shift(before, from, edited.timing)
        val applied = text.applyTo(edited.copy(id = 0, uid = newUid, timing = seriesTiming, rrule = seriesRule, createdAt = 0), master)
        val series = if (sameRule) applied else startOnRule(applied)
        val following = exceptions.filter { it.originalStart >= key }
        val seriesExceptions = if (timeKept) {
            following.mapNotNull { exception -> (if (exception === atKey) text.clear(exception) else exception)?.copy(id = 0, eventId = 0) }
        } else {
            following.filter { it.cancelled }.map { exception ->
                val moved = shift(OccurrenceCalculator.timingAt(master.timing, exception.originalStart), from, edited.timing)
                exception.copy(id = 0, eventId = 0, originalStart = moved.occurrenceKey)
            }
        }
        return Split(shortened, exceptionsBefore(exceptions, key), series, if (sameRule) seriesExceptions else stillInSeries(series, seriesExceptions))
    }

    /**
     * A weekly rule on chosen weekdays starts on the first of them. RFC 5545 counts the start as an
     * occurrence even off those days, which nobody expects from "every Monday and Friday". When the
     * rule ends before that day, the start stays, so the event keeps its one occurrence.
     */
    fun startOnRule(event: Event): Event {
        val rule = RRule.parse(event.rrule ?: return event) ?: return event
        if (rule.freq != Frequency.WEEKLY || rule.byDay.isEmpty() || rule.byDay.any { it.ordinal != 0 }) return event
        val days = rule.byDay.map { it.day }.toSet()
        val first = event.timing.startDate((event.timing as? EventTiming.Timed)?.zone ?: ZoneOffset.UTC)
        if (first.dayOfWeek in days) return event
        val shift = (1L..6L).first { first.plusDays(it).dayOfWeek in days }
        val timing = when (val t = event.timing) {
            is EventTiming.Timed -> EventTiming.Timed(t.start.atZone(t.zone).plusDays(shift).toInstant(), t.end.atZone(t.zone).plusDays(shift).toInstant(), t.zone)
            is EventTiming.AllDay -> EventTiming.AllDay(t.startDate.plusDays(shift), t.endDate.plusDays(shift))
        }
        val moved = event.copy(timing = timing)
        val at = moved.timing.startInstant(ZoneOffset.UTC)
        return if (OccurrenceCalculator.seriesKeys(moved, rule, emptySet(), at, at, limit = 1).isEmpty()) event else moved
    }

    /** How many occurrences of a COUNT-limited series lie before [key]; 0 for other series. */
    fun occurrencesBefore(master: Event, key: Long): Int {
        val rule = RRule.parse(master.rrule ?: return 0) ?: return 0
        if (rule.count == null) return 0
        val until = when (master.timing) {
            is EventTiming.Timed -> Instant.ofEpochMilli(key)
            is EventTiming.AllDay -> LocalDate.ofEpochDay(key).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        // COUNT bounds the expansion; the usual cap on occurrences must not cut the count short.
        return OccurrenceCalculator.seriesKeys(master, rule, emptySet(), master.timing.startInstant(ZoneOffset.UTC), until, limit = Int.MAX_VALUE).count { it < key }
    }

    private fun changedAt(exceptions: List<EventException>, key: Long): EventException? =
        exceptions.firstOrNull { it.originalStart == key && !it.cancelled }

    private val EventException.overridesSomething: Boolean
        get() = cancelled || title != null || location != null || description != null || timing != null

    /**
     * Exceptions whose occurrence the (changed) rule of [event] still produces; the others would
     * show up on their own as stray events.
     */
    fun stillInSeries(event: Event, exceptions: List<EventException>): List<EventException> {
        if (exceptions.isEmpty()) return exceptions
        val rule = RRule.parse(event.rrule ?: return emptyList()) ?: return emptyList()
        val keys = exceptions.map { it.originalStart }
        // The exceptions bound the range; the usual cap on occurrences must not drop the later ones.
        val produced = OccurrenceCalculator.seriesKeys(
            event, rule, emptySet(), keyInstant(event.timing, keys.min()), keyInstant(event.timing, keys.max()), limit = Int.MAX_VALUE,
        ).toSet()
        return exceptions.filter { it.originalStart in produced }
    }

    private fun normalizedRule(event: Event): RRule? {
        val rule = RRule.parse(event.rrule ?: return null) ?: return null
        val day = event.timing.startDate((event.timing as? EventTiming.Timed)?.zone ?: ZoneOffset.UTC).dayOfWeek
        return if (rule.freq == Frequency.WEEKLY && rule.byDay == listOf(WeekdayNum(0, day))) rule.copy(byDay = emptyList()) else rule
    }

    private fun keyInstant(timing: EventTiming, key: Long): Instant = when (timing) {
        is EventTiming.Timed -> Instant.ofEpochMilli(key)
        is EventTiming.AllDay -> LocalDate.ofEpochDay(key).atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    /**
     * The text of the occurrence at [key] that was edited, compared with what the editor showed.
     * [reference] is where the time counts from; a change of the occurrence to another kind of time
     * (all-day in a timed series) is replaced by the edit, so it is dropped as well.
     */
    private class TextEdits(master: Event, shown: EventException?, edited: Event, private val reference: EventTiming) {
        private val title = edited.title.takeIf { it != (shown?.title ?: master.title) }
        private val location = edited.location.takeIf { it != (shown?.location ?: master.location) }
        private val description = edited.description.takeIf { it != (shown?.description ?: master.description) }

        /** [target] with the edited text; what was not edited stays as in [master]. */
        fun applyTo(target: Event, master: Event): Event =
            target.copy(title = title ?: master.title, location = location ?: master.location, description = description ?: master.description)

        /** [exception] without what now comes from the series; null when nothing of it is left. */
        fun clear(exception: EventException): EventException? = exception.copy(
            title = exception.title.takeIf { title == null },
            location = exception.location.takeIf { location == null },
            description = exception.description.takeIf { description == null },
            timing = exception.timing?.takeIf { it::class == reference::class },
        ).takeIf { it.overridesSomething }
    }
}
