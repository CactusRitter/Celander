package com.celander.app.core.ical

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsTodoTest {
    private fun roundTrip(vararg todos: ICalTodo) = IcsParser.parseCalendar(IcsWriter.calendar(emptyList(), todos = todos.toList())).todos

    @Test
    fun aTimedTaskKeepsItsWallClockStateAndReminders() {
        val todo = ICalTodo(
            uid = "t1", summary = "Call; the, dentist", description = "line1\nline2", due = LocalDate.of(2026, 11, 20), dueTime = LocalTime.of(9, 30),
            completed = true, completedAt = 1_700_000_000_000L, reminders = listOf(10, 1440),
        )
        assertEquals(listOf(todo), roundTrip(todo))
    }

    @Test
    fun aTaskDueOnADayOnlyAndOneWithoutDateRoundTrip() {
        val day = ICalTodo(uid = "t2", summary = "Pay rent", due = LocalDate.of(2026, 12, 1))
        val none = ICalTodo(uid = "t3", summary = "Someday")
        val back = roundTrip(day, none)
        assertEquals(day, back[0])
        assertNull(back[1].due)
        assertNull(back[1].dueTime)
        assertFalse(back[1].completed)
    }

    @Test
    fun eventsAndTasksShareOneFileAndOtherFilesAreUnderstood() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:e1\r\nDTSTART:20261120T100000Z\r\nDTEND:20261120T110000Z\r\nSUMMARY:Meet\r\nEND:VEVENT\r\n" +
            "BEGIN:VTODO\r\nUID:t9\r\nSUMMARY:Buy milk\r\nDUE:20261120T180000Z\r\nPERCENT-COMPLETE:100\r\nBEGIN:VALARM\r\nTRIGGER:-PT15M\r\nACTION:DISPLAY\r\nEND:VALARM\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val calendar = IcsParser.parseCalendar(text)
        assertEquals(listOf("e1"), calendar.events.map { it.uid })
        val todo = calendar.todos.single()
        assertEquals("Buy milk", todo.summary)
        assertTrue(todo.completed)
        assertEquals(listOf(15), todo.reminders)
    }
}
