package com.celander.app.calendar.agenda

import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface AgendaRow {
    val key: String
    val date: LocalDate

    data class DayHeader(override val date: LocalDate, val isToday: Boolean) : AgendaRow {
        override val key get() = "h:$date"
    }

    data class Event(override val date: LocalDate, val occurrence: EventOccurrence) : AgendaRow {
        override val key get() = "e:$date:${occurrence.key}"
    }

    data class Task(override val date: LocalDate, val item: TaskItem) : AgendaRow {
        override val key get() = "t:$date:${item.task.id}"
    }

    /** The current moment inside today's list. */
    data class Now(override val date: LocalDate) : AgendaRow {
        override val key get() = "now:$date"
    }

    /** Today without entries. */
    data class EmptyToday(override val date: LocalDate) : AgendaRow {
        override val key get() = "empty:$date"
    }

    /**
     * A run of days without entries, collapsed into one line ("nothing until …"). Keyed by its last
     * day: when the agenda loads earlier days, the first gap starts earlier but stays the same row,
     * so the list keeps its place.
     */
    data class Gap(override val date: LocalDate, val until: LocalDate) : AgendaRow {
        override val key get() = "gap:$until"
    }
}

/** Builds agenda rows for [start, endExclusive). Pure, so it is unit-testable. */
object AgendaBuilder {
    /** The rows of [start, endExclusive) without the "now" divider, which [withNow] adds. */
    fun build(
        start: LocalDate,
        endExclusive: LocalDate,
        occurrences: List<EventOccurrence>,
        tasks: List<TaskItem>,
        today: LocalDate,
        zone: ZoneId,
    ): List<AgendaRow> {
        val byDay = HashMap<LocalDate, MutableList<AgendaRow>>()
        for (o in occurrences) {
            var d = maxOf(o.timing.startDate(zone), start)
            val last = minOf(o.timing.lastDate(zone), endExclusive.minusDays(1))
            while (!d.isAfter(last)) {
                byDay.getOrPut(d) { mutableListOf() } += AgendaRow.Event(d, o)
                d = d.plusDays(1)
            }
        }
        for (t in tasks.distinctBy { it.task.id }) {
            val dueDate = t.task.dueDate ?: continue
            // Open overdue tasks still need doing, so they are listed (and can be checked off) under today.
            val due = if (dueDate.isBefore(today) && !t.task.done) today else dueDate
            if (due.isBefore(start) || !due.isBefore(endExclusive)) continue
            byDay.getOrPut(due) { mutableListOf() } += AgendaRow.Task(due, t)
        }

        val rows = mutableListOf<AgendaRow>()
        var date = start
        while (date.isBefore(endExclusive)) {
            val entries = byDay[date]?.sortedWith(entryOrder(zone)).orEmpty()
            when {
                entries.isNotEmpty() -> {
                    rows += AgendaRow.DayHeader(date, date == today)
                    rows += entries
                    date = date.plusDays(1)
                }
                date == today -> {
                    rows += AgendaRow.DayHeader(date, true)
                    rows += AgendaRow.EmptyToday(date)
                    date = date.plusDays(1)
                }
                else -> {
                    var next = date.plusDays(1)
                    while (next.isBefore(endExclusive) && byDay[next].isNullOrEmpty() && next != today) next = next.plusDays(1)
                    rows += AgendaRow.Gap(date, next.minusDays(1))
                    date = next
                }
            }
        }
        return rows
    }

    private fun sortKey(row: AgendaRow, zone: ZoneId): Pair<Int, Long> = when (row) {
        is AgendaRow.Event -> {
            val allDayLike = row.occurrence.allDay || row.occurrence.timing.spansMultipleDays(zone)
            (if (allDayLike) 0 else 1) to row.occurrence.timing.startInstant(zone).toEpochMilli()
        }
        is AgendaRow.Task -> {
            val time = row.item.task.dueTime
            if (time == null) 0 to Long.MAX_VALUE else 1 to row.date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        else -> 2 to 0L
    }

    private fun entryOrder(zone: ZoneId) = Comparator<AgendaRow> { a, b ->
        val ka = sortKey(a, zone)
        val kb = sortKey(b, zone)
        compareValuesBy(ka, kb, { it.first }, { it.second })
    }

    /**
     * Where the "now" divider goes in [rows]: among today's entries, after those that have already
     * started. Null when today has none. Cheap next to [build], so it can follow the clock alone.
     */
    fun nowIndex(rows: List<AgendaRow>, now: Instant, zone: ZoneId): Int? {
        val header = rows.indexOfFirst { it is AgendaRow.DayHeader && it.isToday }
        // Today without entries says "nothing planned" instead.
        if (header < 0 || rows.getOrNull(header + 1)?.let { isEntry(it) } != true) return null
        var index = header + 1
        while (index < rows.size && isEntry(rows[index]) && !startsAfter(rows[index], now, zone)) index++
        return index
    }

    /** [rows] with the "now" divider at [index] (see [nowIndex]). */
    fun withNow(rows: List<AgendaRow>, index: Int?): List<AgendaRow> {
        if (index == null) return rows
        return rows.subList(0, index) + AgendaRow.Now(rows[index - 1].date) + rows.subList(index, rows.size)
    }

    private fun isEntry(row: AgendaRow): Boolean = row is AgendaRow.Event || row is AgendaRow.Task

    /** Whether [row] begins at a time of day still to come; all-day entries never do. */
    private fun startsAfter(row: AgendaRow, now: Instant, zone: ZoneId): Boolean = when (row) {
        is AgendaRow.Event -> !row.occurrence.allDay && !row.occurrence.timing.spansMultipleDays(zone) &&
            row.occurrence.timing.startInstant(zone).isAfter(now)
        is AgendaRow.Task -> row.item.task.dueTime?.let { row.date.atTime(it).atZone(zone).toInstant().isAfter(now) } ?: false
        else -> false
    }
}
