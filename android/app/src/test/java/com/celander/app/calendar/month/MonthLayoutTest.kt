package com.celander.app.calendar.month

import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.occurrence
import com.celander.app.testing.taskItem
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MonthLayoutTest {
    private val monday = LocalDate.of(2026, 10, 19)

    @Test
    fun multiDayBarIsClippedToTheWeekAndMarkedAsContinuing() {
        val trip = allDayEvent(1, LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 21)).occurrence()
        val segment = MonthLayout.layoutWeek(monday, listOf(trip), emptyList(), BERLIN).segments.single()
        assertEquals(0, segment.startCol)
        assertEquals(2, segment.endCol)
        assertTrue(segment.bar)
        assertTrue(segment.continuesBefore)
        assertFalse(segment.continuesAfter)
    }

    @Test
    fun overlappingEntriesGetSeparateLanesAndBarsComeFirst() {
        val first = allDayEvent(1, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21)).occurrence()
        val second = allDayEvent(2, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22)).occurrence()
        val meeting = timedEvent(3, LocalDateTime.of(2026, 10, 19, 10, 0), 60).occurrence()
        val layout = MonthLayout.layoutWeek(monday, listOf(meeting, second, first), emptyList(), BERLIN)
        val lanes = layout.segments.associate { it.entry.key to it.lane }
        assertEquals(0, lanes.getValue(first.key))
        assertEquals(1, lanes.getValue(second.key))
        // The free slot on Monday in lane 1 is reused by the timed event.
        assertEquals(1, lanes.getValue(meeting.key))
        assertEquals(2, layout.laneCount)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 0, 0, 0), layout.hiddenPerColumn(visibleLanes = 1))
    }

    @Test
    fun tasksWithoutDueDateAreLeftOut() {
        val layout = MonthLayout.layoutWeek(monday, emptyList(), listOf(taskItem(1, null), taskItem(2, LocalDate.of(2026, 10, 25))), BERLIN)
        assertEquals(listOf("task:2"), layout.segments.map { it.entry.key })
        assertEquals(6, layout.segments.single().startCol)
    }
}
