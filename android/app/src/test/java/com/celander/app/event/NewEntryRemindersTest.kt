package com.celander.app.event

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.model.SourceIds
import com.celander.app.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class NewEntryRemindersTest {
    private val global = AppSettings(eventReminders = listOf(10), allDayReminders = listOf(-540, 360), taskReminders = listOf(0)).reminderDefaults
    private val local = CalendarInfo(1, "Personal", "lavender")
    private val work = CalendarInfo(SourceIds.device(1), "Work", "blue", source = CalendarSource.DEVICE, maxReminders = 2)

    @Test
    fun globalDefaultsComeFromTheSettings() {
        assertEquals(ReminderDefaults(listOf(10), listOf(-540, 360), listOf(0)), global)
    }

    @Test
    fun aLocalCalendarWithoutOwnDefaultsGetsTheGlobalOnes() {
        assertEquals(listOf(10), NewEntryReminders.forEntry(local, global, EntryKind.EVENT, allDay = false))
        assertEquals(listOf(-540, 360), NewEntryReminders.forEntry(local, global, EntryKind.EVENT, allDay = true))
        assertEquals(listOf(0), NewEntryReminders.forEntry(local, global, EntryKind.TASK, allDay = false))
        assertEquals(listOf(10), NewEntryReminders.forEntry(null, global, EntryKind.EVENT, allDay = false))
    }

    @Test
    fun ownDefaultsWinAndMayBeEmpty() {
        val calendar = local.copy(reminderDefaults = ReminderDefaults(event = emptyList(), allDay = listOf(1440), task = listOf(15)))
        assertEquals(emptyList<Int>(), NewEntryReminders.forEntry(calendar, global, EntryKind.EVENT, allDay = false))
        assertEquals(listOf(1440), NewEntryReminders.forEntry(calendar, global, EntryKind.EVENT, allDay = true))
        assertEquals(listOf(15), NewEntryReminders.forEntry(calendar, global, EntryKind.TASK, allDay = false))
    }

    @Test
    fun thePhonesCalendarsDropSameDayRemindersAndCapThem() {
        assertEquals(listOf(360), NewEntryReminders.forEntry(work, global, EntryKind.EVENT, allDay = true))
        val own = work.copy(reminderDefaults = ReminderDefaults(event = listOf(5, 10, 30), allDay = emptyList(), task = emptyList()))
        assertEquals(listOf(5, 10), NewEntryReminders.forEntry(own, global, EntryKind.EVENT, allDay = false))
        assertEquals(listOf(5, 10, 30), NewEntryReminders.fit(local, listOf(5, 10, 30)))
    }
}
