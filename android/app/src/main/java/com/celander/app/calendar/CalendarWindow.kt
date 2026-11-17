package com.celander.app.calendar

import androidx.compose.runtime.Immutable
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What happens on one day, already sorted for display. */
@Immutable
data class DayContent(
    val allDay: List<EventOccurrence>,
    val timed: List<EventOccurrence>,
    val tasks: List<TaskItem>,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && timed.isEmpty() && tasks.isEmpty()
    val count: Int get() = allDay.size + timed.size + tasks.size

    companion object {
        val EMPTY = DayContent(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Occurrences and open tasks of a date window. Built once per visible window off the main thread;
 * views only read from it.
 */
@Immutable
class CalendarWindow(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val occurrences: List<EventOccurrence>,
    val tasks: List<TaskItem>,
    val zone: ZoneId,
) {
    private val days: Map<LocalDate, DayContent> = buildDays()

    fun day(date: LocalDate): DayContent = days[date] ?: DayContent.EMPTY

    private fun buildDays(): Map<LocalDate, DayContent> {
        val allDay = HashMap<LocalDate, MutableList<EventOccurrence>>()
        val timed = HashMap<LocalDate, MutableList<EventOccurrence>>()
        for (occurrence in occurrences) {
            val first = maxOf(occurrence.timing.startDate(zone), start)
            val last = minOf(occurrence.timing.lastDate(zone), endExclusive.minusDays(1))
            var d = first
            while (!d.isAfter(last)) {
                val target = if (occurrence.allDay || occurrence.timing.spansMultipleDays(zone)) allDay else timed
                target.getOrPut(d) { mutableListOf() } += occurrence
                d = d.plusDays(1)
            }
        }
        val taskMap = tasks.filter { it.task.dueDate != null }.groupBy { it.task.dueDate!! }
        val keys = allDay.keys + timed.keys + taskMap.keys
        return keys.associateWith { date ->
            DayContent(
                allDay = allDay[date].orEmpty().sortedWith(compareBy({ it.timing.startInstant(zone) }, { it.title })),
                timed = timed[date].orEmpty().sortedWith(compareBy({ it.timing.startInstant(zone) }, { it.timing.endInstant(zone) }, { it.title })),
                tasks = taskMap[date].orEmpty().sortedWith(compareBy({ it.task.dueTime ?: LocalTime.MAX }, { it.task.title })),
            )
        }
    }

    companion object {
        fun empty(start: LocalDate, endExclusive: LocalDate, zone: ZoneId) = CalendarWindow(start, endExclusive, emptyList(), emptyList(), zone)
    }
}
