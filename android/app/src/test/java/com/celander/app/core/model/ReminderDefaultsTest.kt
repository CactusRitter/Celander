package com.celander.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReminderDefaultsTest {
    private val global = ReminderDefaults(event = listOf(10), allDay = listOf(360), task = listOf(0))
    private val own = ReminderDefaults(event = listOf(5, 60), allDay = emptyList(), task = listOf(30))

    @Test
    fun eachKindHasItsList() {
        assertEquals(listOf(5, 60), own.forEntry(EntryKind.EVENT, allDay = false))
        assertEquals(emptyList<Int>(), own.forEntry(EntryKind.EVENT, allDay = true))
        // A task has no all-day variant.
        assertEquals(listOf(30), own.forEntry(EntryKind.TASK, allDay = true))
    }

    @Test
    fun aCalendarWithoutOwnDefaultsUsesTheGlobalOnes() {
        assertSame(global, ReminderDefaults.effective(CalendarInfo(1, "Personal", "lavender"), global))
        assertSame(global, ReminderDefaults.effective(null, global))
    }

    @Test
    fun aCalendarWithOwnDefaultsUsesThem() {
        assertSame(own, ReminderDefaults.effective(CalendarInfo(1, "Work", "blue", reminderDefaults = own), global))
    }
}
