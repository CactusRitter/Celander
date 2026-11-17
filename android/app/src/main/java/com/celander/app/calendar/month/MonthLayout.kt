package com.celander.app.calendar.month

import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** An entry of the month grid: an event occurrence or an open task. */
sealed interface MonthEntry {
    val key: String
    val colorKey: String
    val title: String

    data class Event(val occurrence: EventOccurrence) : MonthEntry {
        override val key get() = occurrence.key
        override val colorKey get() = occurrence.colorKey
        override val title get() = occurrence.title
    }

    data class Task(val item: TaskItem) : MonthEntry {
        override val key get() = "task:${item.task.id}"
        override val colorKey get() = item.colorKey
        override val title get() = item.task.title
    }
}

/**
 * Placement of an entry in one week row: columns [startCol]..[endCol] (inclusive) in [lane].
 * [bar] entries (all-day or multi-day) are drawn as filled bars across their days.
 */
data class WeekSegment(
    val entry: MonthEntry,
    val startCol: Int,
    val endCol: Int,
    val lane: Int,
    val bar: Boolean,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
)

data class WeekLayout(val segments: List<WeekSegment>, val laneCount: Int) {
    /** Number of entries per column (0..6) that sit in lanes at or beyond [visibleLanes]. */
    fun hiddenPerColumn(visibleLanes: Int): IntArray {
        val hidden = IntArray(7)
        for (s in segments) if (s.lane >= visibleLanes) for (c in s.startCol..s.endCol) hidden[c]++
        return hidden
    }
}

/** Assigns lanes so multi-day bars run continuously and nothing overlaps. Pure function. */
object MonthLayout {
    fun layoutWeek(weekStart: LocalDate, occurrences: List<EventOccurrence>, tasks: List<TaskItem>, zone: ZoneId): WeekLayout {
        val weekEnd = weekStart.plusDays(6)

        data class Candidate(val entry: MonthEntry, val start: LocalDate, val end: LocalDate, val bar: Boolean, val order: Long)

        val candidates = buildList {
            for (o in occurrences) {
                val start = o.timing.startDate(zone)
                val end = o.timing.lastDate(zone)
                if (end.isBefore(weekStart) || start.isAfter(weekEnd)) continue
                val bar = o.allDay || o.timing.spansMultipleDays(zone)
                add(Candidate(MonthEntry.Event(o), start, end, bar, o.timing.startInstant(zone).toEpochMilli()))
            }
            for (t in tasks) {
                val due = t.task.dueDate ?: continue
                if (due.isBefore(weekStart) || due.isAfter(weekEnd)) continue
                val time = t.task.dueTime ?: LocalTime.MAX
                add(Candidate(MonthEntry.Task(t), due, due, false, due.atTime(time).atZone(zone).toInstant().toEpochMilli()))
            }
        }.sortedWith(
            compareBy<Candidate>({ !it.bar }, { maxOf(it.start, weekStart) })
                .thenByDescending { ChronoUnit.DAYS.between(it.start, it.end) }
                .thenBy { it.order }
                .thenBy { it.entry.title },
        )

        val occupied = mutableListOf<BooleanArray>()
        val segments = candidates.map { c ->
            val startCol = ChronoUnit.DAYS.between(weekStart, maxOf(c.start, weekStart)).toInt()
            val endCol = ChronoUnit.DAYS.between(weekStart, minOf(c.end, weekEnd)).toInt()
            var lane = occupied.indexOfFirst { row -> (startCol..endCol).none { row[it] } }
            if (lane < 0) {
                occupied += BooleanArray(7)
                lane = occupied.lastIndex
            }
            for (col in startCol..endCol) occupied[lane][col] = true
            WeekSegment(
                entry = c.entry,
                startCol = startCol,
                endCol = endCol,
                lane = lane,
                bar = c.bar,
                continuesBefore = c.start.isBefore(weekStart),
                continuesAfter = c.end.isAfter(weekEnd),
            )
        }
        return WeekLayout(segments, occupied.size)
    }
}
