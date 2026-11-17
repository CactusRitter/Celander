package com.celander.app.calendar.agenda

import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.occurrence
import com.celander.app.testing.taskItem
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AgendaBuilderTest {
    private val start = LocalDate.of(2026, 10, 19)
    private val end = LocalDate.of(2026, 10, 26)
    private val today = LocalDate.of(2026, 10, 21)
    private val noon = LocalDateTime.of(2026, 10, 21, 12, 0).atZone(BERLIN).toInstant()

    /** The rows as the agenda shows them at noon: built, then the "now" divider added. */
    private fun build(occurrences: List<EventOccurrence>, tasks: List<TaskItem>): List<AgendaRow> {
        val rows = AgendaBuilder.build(start, end, occurrences, tasks, today, BERLIN)
        return AgendaBuilder.withNow(rows, AgendaBuilder.nowIndex(rows, noon, BERLIN))
    }

    private fun describe(rows: List<AgendaRow>) = rows.map { row ->
        when (row) {
            is AgendaRow.DayHeader -> "H${row.date.dayOfMonth}"
            is AgendaRow.Event -> "E${row.occurrence.event.id}"
            is AgendaRow.Task -> "T${row.item.task.id}"
            is AgendaRow.Now -> "NOW"
            is AgendaRow.EmptyToday -> "EMPTY"
            is AgendaRow.Gap -> "GAP${row.date.dayOfMonth}-${row.until.dayOfMonth}"
        }
    }

    @Test
    fun emptyDaysCollapseAndNowSitsBetweenPastAndFuture() {
        val rows = build(
            listOf(
                timedEvent(1, LocalDateTime.of(2026, 10, 19, 10, 0), 60).occurrence(),
                timedEvent(2, LocalDateTime.of(2026, 10, 21, 8, 0), 60).occurrence(),
                timedEvent(3, LocalDateTime.of(2026, 10, 21, 18, 0), 60).occurrence(),
            ),
            listOf(taskItem(4, today, LocalTime.of(15, 0))),
        )
        assertEquals(listOf("H19", "E1", "GAP20-20", "H21", "E2", "NOW", "T4", "E3", "GAP22-25"), describe(rows))
    }

    @Test
    fun todayIsShownEvenWithoutEntries() {
        val rows = build(emptyList(), emptyList())
        assertEquals(listOf("GAP19-20", "H21", "EMPTY", "GAP22-25"), describe(rows))
    }

    @Test
    fun multiDayEventsAppearOnEveryDayAndBeforeTimedEntries() {
        val rows = build(
            listOf(
                timedEvent(1, LocalDateTime.of(2026, 10, 23, 9, 0), 60).occurrence(),
                allDayEvent(2, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23)).occurrence(),
            ),
            listOf(taskItem(3, LocalDate.of(2026, 10, 23))),
        )
        assertEquals(
            listOf("GAP19-20", "H21", "EMPTY", "H22", "E2", "H23", "E2", "T3", "E1", "GAP24-25"),
            describe(rows),
        )
    }

    @Test
    fun openOverdueTasksAreListedUnderToday() {
        val overdue = taskItem(7, LocalDate.of(2026, 10, 2))
        val done = taskItem(8, LocalDate.of(2026, 10, 20)).let { it.copy(task = it.task.copy(done = true)) }
        val rows = build(emptyList(), listOf(overdue, overdue, done))
        assertEquals(listOf("GAP19-19", "H20", "T8", "H21", "T7", "NOW", "GAP22-25"), describe(rows))
    }

    @Test
    fun theNowDividerFollowsTheClockOnRowsBuiltOnce() {
        val rows = AgendaBuilder.build(
            start,
            end,
            listOf(
                allDayEvent(1, today).occurrence(),
                timedEvent(2, LocalDateTime.of(2026, 10, 21, 8, 0), 60).occurrence(),
                timedEvent(3, LocalDateTime.of(2026, 10, 21, 18, 0), 60).occurrence(),
            ),
            emptyList(),
            today,
            BERLIN,
        )
        fun at(hour: Int) = describe(AgendaBuilder.withNow(rows, AgendaBuilder.nowIndex(rows, today.atTime(hour, 0).atZone(BERLIN).toInstant(), BERLIN)))
        assertEquals(listOf("GAP19-20", "H21", "E1", "NOW", "E2", "E3", "GAP22-25"), at(7))
        assertEquals(listOf("GAP19-20", "H21", "E1", "E2", "NOW", "E3", "GAP22-25"), at(12))
        assertEquals(listOf("GAP19-20", "H21", "E1", "E2", "E3", "NOW", "GAP22-25"), at(20))
    }

    @Test
    fun todayWithoutEntriesHasNoNowDivider() {
        val rows = AgendaBuilder.build(start, end, emptyList(), emptyList(), today, BERLIN)
        assertNull(AgendaBuilder.nowIndex(rows, noon, BERLIN))
    }

    @Test
    fun theFirstGapKeepsItsKeyWhenEarlierDaysAreLoaded() {
        val events = listOf(
            timedEvent(1, LocalDateTime.of(2026, 10, 3, 9, 0), 60).occurrence(),
            timedEvent(2, LocalDateTime.of(2026, 10, 23, 9, 0), 60).occurrence(),
        )
        val before = AgendaBuilder.build(start, end, events, emptyList(), today, BERLIN)
        val extended = AgendaBuilder.build(start.minusDays(30), end, events, emptyList(), today, BERLIN)
        // The gap before the 21st now starts on the 4th instead of the 19th, but is the same row.
        val firstGap = before.first() as AgendaRow.Gap
        val sameGap = extended.single { it is AgendaRow.Gap && it.until == firstGap.until } as AgendaRow.Gap
        assertEquals(LocalDate.of(2026, 10, 4), sameGap.date)
        assertEquals(firstGap.key, sameGap.key)
    }
}
