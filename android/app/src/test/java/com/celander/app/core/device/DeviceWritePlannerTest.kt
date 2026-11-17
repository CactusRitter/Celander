package com.celander.app.core.device

import com.celander.app.core.device.ProviderColumns as C
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DeviceWritePlannerTest {
    private val day = 86_400_000L
    private val masterRow = 42L
    private val calendarRow = 7L
    private val alerts = ReminderPolicy(maxReminders = 5, allowedReminders = "0,1,2")

    private fun Event.onDevice(): Event = copy(id = SourceIds.device(masterRow), calendarId = SourceIds.device(calendarRow))

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: ZoneId = BERLIN): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The master row as the provider returns it: Long flags, ISO durations, null for empty text. */
    private fun columnsOf(event: Event): Map<String, Any?> = DeviceWritePlanner.eventValues(event).mapValues { (column, value) ->
        when {
            column == C.ALL_DAY -> (value as Int).toLong()
            column == C.DURATION && value == "P3600S" -> "PT1H"
            value == "" -> null
            else -> value
        }
    }

    /** What the provider needs to re-expand instances; sent whole whenever one of them changes. */
    private fun timingOf(event: Event): Map<String, Any?> = DeviceWritePlanner.eventValues(event)
        .filterKeys { it in setOf(C.DTSTART, C.DTEND, C.DURATION, C.EVENT_TIMEZONE, C.ALL_DAY, C.RRULE) }

    private fun stateOf(master: Event, exceptions: List<EventException> = emptyList(), exDates: List<Long> = emptyList()) =
        DeviceEventState(master, exceptions, columnsOf(master), exDates)

    private fun exception(row: Long, key: Long, cancelled: Boolean = false, title: String? = null) =
        EventException(id = SourceIds.device(row), eventId = SourceIds.device(masterRow), originalStart = key, cancelled = cancelled, title = title)

    private val weekly = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 60, rrule = "FREQ=WEEKLY").onDevice()

    // Writes table

    @Test
    fun timedSingleWritesDtendAndClearsDurationAndRule() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 90).onDevice()
        val insert = DeviceWritePlanner.insert(event, alerts).single() as ProviderOp.InsertEvent
        assertEquals(
            mapOf(
                C.DTSTART to at(2026, 10, 5, 9),
                C.DTEND to at(2026, 10, 5, 10, 30),
                C.DURATION to null,
                C.EVENT_TIMEZONE to "Europe/Berlin",
                C.ALL_DAY to 0,
                C.RRULE to null,
                C.TITLE to "Event 1",
                C.EVENT_LOCATION to "",
                C.DESCRIPTION to "",
                C.CALENDAR_ID to calendarRow,
            ),
            insert.values,
        )
    }

    @Test
    fun timedSeriesWritesDurationInSecondsAndKeepsRuleExtras() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 45, rrule = "FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO").onDevice()
        val values = (DeviceWritePlanner.insert(event, alerts).single() as ProviderOp.InsertEvent).values
        assertEquals("P2700S", values[C.DURATION])
        assertEquals(null, values[C.DTEND])
        assertTrue(C.DTEND in values)
        assertEquals("FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO", values[C.RRULE])
        assertEquals("Europe/Berlin", values[C.EVENT_TIMEZONE])
    }

    @Test
    fun allDaySingleIsStoredInUtcWithExclusiveEnd() {
        val event = allDayEvent(1, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 25)).onDevice()
        val values = DeviceWritePlanner.eventValues(event)
        assertEquals(LocalDate.of(2026, 10, 23).toEpochDay() * day, values[C.DTSTART])
        assertEquals(LocalDate.of(2026, 10, 26).toEpochDay() * day, values[C.DTEND])
        assertEquals("UTC", values[C.EVENT_TIMEZONE])
        assertEquals(1, values[C.ALL_DAY])
        assertEquals(null, values[C.DURATION])
    }

    @Test
    fun allDaySeriesWritesDurationInDays() {
        val event = allDayEvent(1, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 25), rrule = "FREQ=YEARLY").onDevice()
        val values = DeviceWritePlanner.eventValues(event)
        assertEquals("P3D", values[C.DURATION])
        assertEquals(null, values[C.DTEND])
        assertEquals("UTC", values[C.EVENT_TIMEZONE])
        assertEquals("FREQ=YEARLY", values[C.RRULE])
    }

    @Test
    fun insertWritesRemindersByBackReference() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 30).onDevice().copy(reminders = listOf(10))
        val ops = DeviceWritePlanner.insert(event, alerts)
        assertEquals(ProviderOp.InsertReminders(EventRef.BackRef(0), listOf(10), C.METHOD_ALERT), ops[1])
    }

    // Updates

    @Test
    fun updateWritesOnlyChangedColumns() {
        val master = weekly.copy(description = "")
        val ops = DeviceWritePlanner.update(stateOf(master), master.copy(title = "Renamed"), alerts)
        assertEquals(listOf(ProviderOp.UpdateEvent(masterRow, mapOf(C.TITLE to "Renamed"))), ops)
    }

    @Test
    fun unchangedEventWritesNothing() {
        assertEquals(emptyList<ProviderOp>(), DeviceWritePlanner.update(stateOf(weekly), weekly, alerts))
    }

    @Test
    fun columnsMissingFromTheLoadedRowAreWritten() {
        val state = DeviceEventState(weekly, columns = columnsOf(weekly) - C.DESCRIPTION)
        val update = DeviceWritePlanner.update(state, weekly, alerts).single() as ProviderOp.UpdateEvent
        assertEquals(mapOf(C.DESCRIPTION to weekly.description), update.values)
    }

    @Test
    fun changedEndAlsoSendsStartSoTheProviderUpdatesInstances() {
        // The provider skips instance updates without DTSTART: the day view kept the old end.
        val single = timedEvent(1, LocalDateTime.of(2026, 10, 23, 14, 30), 7 * 60).onDevice()
        val edited = single.copy(timing = EventTiming.Timed(Instant.ofEpochMilli(at(2026, 10, 23, 14, 30)), Instant.ofEpochMilli(at(2026, 10, 23, 23, 30)), BERLIN))
        val update = DeviceWritePlanner.update(stateOf(single), edited, alerts).single() as ProviderOp.UpdateEvent
        assertEquals(
            mapOf(
                C.DTSTART to at(2026, 10, 23, 14, 30),
                C.DTEND to at(2026, 10, 23, 23, 30),
                C.DURATION to null,
                C.EVENT_TIMEZONE to "Europe/Berlin",
                C.ALL_DAY to 0,
                C.RRULE to null,
            ),
            update.values,
        )
    }

    @Test
    fun changedExceptionEndAlsoSendsItsStart() {
        val key = at(2026, 10, 12, 9)
        val state = stateOf(weekly, listOf(exception(100, key, title = "Moved")))
        val timing = EventTiming.Timed(Instant.ofEpochMilli(key), Instant.ofEpochMilli(at(2026, 10, 12, 11)), BERLIN)
        val update = DeviceWritePlanner.editThis(state, key, weekly.copy(title = "Moved", timing = timing)).single() as ProviderOp.UpdateEvent
        assertEquals(mapOf(C.DTSTART to key, C.DTEND to at(2026, 10, 12, 11), C.EVENT_TIMEZONE to "Europe/Berlin", C.ALL_DAY to 0), update.values)
    }

    @Test
    fun singleToRecurringSwapsDtendForDuration() {
        val single = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 60).onDevice()
        val update = DeviceWritePlanner.update(stateOf(single), single.copy(rrule = "FREQ=DAILY"), alerts).single() as ProviderOp.UpdateEvent
        assertEquals(
            mapOf(C.DTSTART to at(2026, 10, 5, 9), C.DTEND to null, C.DURATION to "P3600S", C.EVENT_TIMEZONE to "Europe/Berlin", C.ALL_DAY to 0, C.RRULE to "FREQ=DAILY"),
            update.values,
        )
    }

    @Test
    fun recurringToSingleDeletesExceptionRows() {
        val exceptions = listOf(exception(100, at(2026, 10, 12, 9), cancelled = true), exception(101, at(2026, 10, 19, 9), title = "X"))
        val ops = DeviceWritePlanner.update(stateOf(weekly, exceptions), weekly.copy(rrule = null), alerts)
        assertEquals(
            listOf(
                ProviderOp.UpdateEvent(masterRow, timingOf(weekly.copy(rrule = null))),
                ProviderOp.DeleteEvent(100),
                ProviderOp.DeleteEvent(101),
            ),
            ops,
        )
    }

    @Test
    fun timedToAllDayRewritesTimingInUtc() {
        val single = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 60).onDevice()
        val edited = single.copy(timing = EventTiming.AllDay(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)))
        val update = DeviceWritePlanner.update(stateOf(single), edited, alerts).single() as ProviderOp.UpdateEvent
        assertEquals(
            mapOf(
                C.DTSTART to LocalDate.of(2026, 10, 5).toEpochDay() * day,
                C.DTEND to LocalDate.of(2026, 10, 6).toEpochDay() * day,
                C.DURATION to null,
                C.EVENT_TIMEZONE to "UTC",
                C.ALL_DAY to 1,
                C.RRULE to null,
            ),
            update.values,
        )
    }

    // Reminders

    @Test
    fun remindersAreNormalizedAndCapped() {
        val policy = ReminderPolicy(maxReminders = 3, allowedReminders = "0,1")
        assertEquals(listOf(0, 10, 30), policy.normalize(listOf(30, -540, 10, 30, 0, 60)))
        assertEquals(listOf(1, 2, 3, 4, 5), ReminderPolicy().normalize(listOf(6, 5, 4, 3, 2, 1)))
    }

    @Test
    fun methodIsAlertOnlyWhenAllowed() {
        assertEquals(C.METHOD_ALERT, ReminderPolicy(allowedReminders = "0, 1,2").method)
        assertEquals(C.METHOD_DEFAULT, ReminderPolicy(allowedReminders = "0,2").method)
        assertEquals(C.METHOD_DEFAULT, ReminderPolicy(allowedReminders = "10").method)
        assertEquals(C.METHOD_DEFAULT, ReminderPolicy().method)
    }

    @Test
    fun changedReminderSetReplacesAlertRowsAndKeepsEmail() {
        val master = weekly.copy(reminders = listOf(10, -540))
        val ops = DeviceWritePlanner.update(stateOf(master), master.copy(reminders = listOf(30, 10, 30)), ReminderPolicy(allowedReminders = "0,2"))
        assertEquals(
            listOf(
                ProviderOp.DeleteReminders(masterRow, setOf(C.METHOD_DEFAULT, C.METHOD_ALERT)),
                ProviderOp.InsertReminders(EventRef.Existing(masterRow), listOf(10, 30), C.METHOD_DEFAULT),
            ),
            ops,
        )
        assertFalse(C.METHOD_EMAIL in (ops[0] as ProviderOp.DeleteReminders).methods)
    }

    @Test
    fun sameReminderSetWritesNoReminders() {
        val master = weekly.copy(reminders = listOf(10, 30, -540))
        assertEquals(emptyList<ProviderOp>(), DeviceWritePlanner.update(stateOf(master), master.copy(reminders = listOf(30, 10)), alerts))
    }

    @Test
    fun clearedRemindersOnlyDelete() {
        val master = weekly.copy(reminders = listOf(10))
        assertEquals(listOf(ProviderOp.DeleteReminders(masterRow)), DeviceWritePlanner.update(stateOf(master), master.copy(reminders = emptyList()), alerts))
    }

    // This

    @Test
    fun editThisInsertsAnException() {
        val key = at(2026, 10, 12, 9)
        val edited = weekly.copy(title = "Moved", timing = EventTiming.Timed(Instant.ofEpochMilli(at(2026, 10, 12, 11)), Instant.ofEpochMilli(at(2026, 10, 12, 12)), BERLIN))
        val op = DeviceWritePlanner.editThis(stateOf(weekly), key, edited).single()
        assertEquals(
            ProviderOp.InsertException(
                masterRow,
                mapOf(
                    C.ORIGINAL_INSTANCE_TIME to key,
                    C.DTSTART to at(2026, 10, 12, 11),
                    C.DTEND to at(2026, 10, 12, 12),
                    C.EVENT_TIMEZONE to "Europe/Berlin",
                    C.ALL_DAY to 0,
                    C.TITLE to "Moved",
                    C.EVENT_LOCATION to "",
                    C.DESCRIPTION to "",
                ),
            ),
            op,
        )
    }

    @Test
    fun editThisUpdatesAnExistingExceptionRow() {
        val key = at(2026, 10, 12, 9)
        val state = stateOf(weekly, listOf(exception(100, key, title = "First")))
        val edited = weekly.copy(title = "Second", timing = OccurrenceCalculator.timingAt(weekly.timing, key))
        assertEquals(listOf(ProviderOp.UpdateEvent(100, mapOf(C.TITLE to "Second"))), DeviceWritePlanner.editThis(state, key, edited))
    }

    @Test
    fun deleteThisInsertsACancelledException() {
        val key = at(2026, 10, 12, 9)
        val op = DeviceWritePlanner.deleteThis(stateOf(weekly), key).single()
        assertEquals(
            ProviderOp.InsertException(masterRow, mapOf<String, Any?>(C.ORIGINAL_INSTANCE_TIME to key, C.STATUS to 2)),
            op,
        )
    }

    @Test
    fun deleteThisCancelsAnExistingRowInsteadOfDeletingIt() {
        val key = at(2026, 10, 12, 9)
        val state = stateOf(weekly, listOf(exception(100, key, title = "Changed")))
        assertEquals(listOf(ProviderOp.UpdateEvent(100, mapOf(C.STATUS to 2))), DeviceWritePlanner.deleteThis(state, key))
        val cancelled = stateOf(weekly, listOf(exception(100, key, cancelled = true)))
        assertEquals(emptyList<ProviderOp>(), DeviceWritePlanner.deleteThis(cancelled, key))
    }

    @Test
    fun allDayOccurrenceKeysBecomeMillis() {
        val series = allDayEvent(1, LocalDate.of(2026, 10, 1), rrule = "FREQ=DAILY").onDevice()
        val key = LocalDate.of(2026, 10, 3).toEpochDay()
        val op = DeviceWritePlanner.deleteThis(stateOf(series), key).single() as ProviderOp.InsertException
        assertEquals(key * day, op.values[C.ORIGINAL_INSTANCE_TIME])
        assertFalse("the provider rejects it", C.ORIGINAL_ALL_DAY in op.values)
    }

    // This and following

    private val counted = timedEvent(1, LocalDateTime.of(2026, 10, 5, 9, 0), 60, rrule = "FREQ=WEEKLY;COUNT=10").onDevice().copy(reminders = listOf(10))
    private val followingExceptions = listOf(
        exception(100, at(2026, 10, 12, 9), cancelled = true),
        exception(101, at(2026, 10, 26, 9), cancelled = true),
        exception(102, at(2026, 11, 2, 9), title = "Moved"),
    )

    @Test
    fun editFollowingCapsMasterAndStartsANewSeries() {
        val key = at(2026, 10, 19, 9)
        val edited = counted.copy(title = "New", timing = OccurrenceCalculator.timingAt(counted.timing, key))
        val plan = DeviceWritePlanner.editFollowing(stateOf(counted, followingExceptions, exDates = listOf(at(2026, 11, 9, 9))), key, edited, alerts)

        assertEquals(ProviderOp.UpdateEvent(masterRow, timingOf(counted.copy(rrule = "FREQ=WEEKLY;UNTIL=20261019T065959Z"))), plan.ops[0])
        assertEquals(listOf(ProviderOp.DeleteEvent(101), ProviderOp.DeleteEvent(102)), plan.ops.subList(1, 3))
        val insert = plan.ops[3] as ProviderOp.InsertEvent
        assertEquals(3, plan.seriesIndex)
        assertEquals("FREQ=WEEKLY;COUNT=8", insert.values[C.RRULE])
        assertEquals("20261026T080000Z,20261109T080000Z", insert.values[C.EXDATE])
        assertEquals(at(2026, 10, 19, 9), insert.values[C.DTSTART])
        assertEquals("New", insert.values[C.TITLE])
        assertEquals(calendarRow, insert.values[C.CALENDAR_ID])
        assertEquals(ProviderOp.InsertReminders(EventRef.BackRef(3), listOf(10), C.METHOD_ALERT), plan.ops[4])
        assertEquals(5, plan.ops.size)

        val followUp = plan.followUp(500).single() as ProviderOp.InsertException
        assertEquals(500L, followUp.masterId)
        assertEquals(at(2026, 11, 2, 9), followUp.values[C.ORIGINAL_INSTANCE_TIME])
        assertEquals(at(2026, 11, 2, 9), followUp.values[C.DTSTART])
        assertEquals("Moved", followUp.values[C.TITLE])
    }

    @Test
    fun editFollowingWithTimeChangeShiftsCancelledDatesAndDropsModified() {
        val key = at(2026, 10, 19, 9)
        val timing = EventTiming.Timed(Instant.ofEpochMilli(at(2026, 10, 19, 10)), Instant.ofEpochMilli(at(2026, 10, 19, 11)), BERLIN)
        val plan = DeviceWritePlanner.editFollowing(
            stateOf(counted, followingExceptions, exDates = listOf(at(2026, 10, 12, 9), at(2026, 11, 9, 9))),
            key,
            counted.copy(timing = timing),
            alerts,
        )
        val insert = plan.ops.filterIsInstance<ProviderOp.InsertEvent>().single()
        assertEquals("20261026T090000Z,20261109T090000Z", insert.values[C.EXDATE])
        assertEquals(emptyList<ProviderOp>(), plan.followUp(500))
    }

    @Test
    fun editFollowingWithANewRuleKeepsItWhole() {
        val key = at(2026, 10, 19, 9)
        val edited = counted.copy(rrule = "FREQ=DAILY;COUNT=3", timing = OccurrenceCalculator.timingAt(counted.timing, key))
        val insert = DeviceWritePlanner.editFollowing(stateOf(counted), key, edited, alerts).ops.filterIsInstance<ProviderOp.InsertEvent>().single()
        assertEquals("FREQ=DAILY;COUNT=3", insert.values[C.RRULE])
        assertFalse(C.EXDATE in insert.values)
    }

    @Test
    fun editFollowingAllDaySeriesWritesMidnightUtcExDates() {
        val series = allDayEvent(1, LocalDate.of(2026, 10, 1), rrule = "FREQ=DAILY").onDevice()
        val key = LocalDate.of(2026, 10, 10).toEpochDay()
        val state = stateOf(series, listOf(exception(100, LocalDate.of(2026, 10, 12).toEpochDay(), cancelled = true)))
        val plan = DeviceWritePlanner.editFollowing(state, key, series.copy(title = "Later", timing = OccurrenceCalculator.timingAt(series.timing, key)), alerts)
        assertEquals(ProviderOp.UpdateEvent(masterRow, timingOf(series.copy(rrule = "FREQ=DAILY;UNTIL=20261009"))), plan.ops[0])
        val insert = plan.ops.filterIsInstance<ProviderOp.InsertEvent>().single()
        assertEquals("20261012T000000Z", insert.values[C.EXDATE])
        assertEquals("P1D", insert.values[C.DURATION])
    }

    @Test
    fun editFollowingFromTheFirstOccurrenceEditsAll() {
        val key = counted.timing.occurrenceKey
        val plan = DeviceWritePlanner.editFollowing(stateOf(counted), key, counted.copy(title = "All"), alerts)
        assertEquals(listOf(ProviderOp.UpdateEvent(masterRow, mapOf(C.TITLE to "All"))), plan.ops)
        assertEquals(null, plan.seriesIndex)
        assertEquals(emptyList<ProviderOp>(), plan.followUp(500))
    }

    @Test
    fun deleteFollowingCapsAndDeletesLaterExceptionRows() {
        val ops = DeviceWritePlanner.deleteFollowing(stateOf(counted, followingExceptions), at(2026, 10, 19, 9))
        assertEquals(
            listOf(
                ProviderOp.UpdateEvent(masterRow, timingOf(counted.copy(rrule = "FREQ=WEEKLY;UNTIL=20261019T065959Z"))),
                ProviderOp.DeleteEvent(101),
                ProviderOp.DeleteEvent(102),
            ),
            ops,
        )
    }

    @Test
    fun deleteFollowingFromTheFirstOccurrenceDeletesTheMaster() {
        assertEquals(listOf(ProviderOp.DeleteEvent(masterRow)), DeviceWritePlanner.deleteFollowing(stateOf(counted), counted.timing.occurrenceKey))
    }

    // All

    @Test
    fun editAllWithoutTimeChangeKeepsExceptions() {
        val key = at(2026, 10, 19, 9)
        val state = stateOf(weekly, followingExceptions)
        val edited = weekly.copy(title = "Renamed", timing = OccurrenceCalculator.timingAt(weekly.timing, key))
        assertEquals(listOf(ProviderOp.UpdateEvent(masterRow, mapOf(C.TITLE to "Renamed"))), DeviceWritePlanner.editAll(state, key, edited, alerts))
    }

    @Test
    fun editAllWithTimeChangeShiftsMasterAndRecreatesCancelledOccurrences() {
        val key = at(2026, 10, 19, 9)
        val state = stateOf(weekly, listOf(exception(100, at(2026, 10, 12, 9), cancelled = true), exception(101, at(2026, 11, 2, 9), cancelled = true)))
        val timing = EventTiming.Timed(Instant.ofEpochMilli(at(2026, 10, 19, 10)), Instant.ofEpochMilli(at(2026, 10, 19, 11)), BERLIN)
        val ops = DeviceWritePlanner.editAll(state, key, weekly.copy(timing = timing), alerts)
        assertEquals(
            listOf(
                ProviderOp.UpdateEvent(masterRow, timingOf(weekly.copy(timing = EventTiming.Timed(Instant.ofEpochMilli(at(2026, 10, 5, 10)), Instant.ofEpochMilli(at(2026, 10, 5, 11)), BERLIN)))),
                ProviderOp.InsertException(masterRow, mapOf<String, Any?>(C.ORIGINAL_INSTANCE_TIME to at(2026, 10, 12, 10), C.STATUS to 2)),
                ProviderOp.InsertException(masterRow, mapOf<String, Any?>(C.ORIGINAL_INSTANCE_TIME to at(2026, 11, 2, 10), C.STATUS to 2)),
            ),
            ops,
        )
    }

    private val movedTiming = EventTiming.Timed(Instant.ofEpochMilli(at(2026, 10, 19, 11)), Instant.ofEpochMilli(at(2026, 10, 19, 12)), BERLIN)
    private val moved = EventException(id = SourceIds.device(101), eventId = SourceIds.device(masterRow), originalStart = at(2026, 10, 19, 9), location = "Room B", timing = movedTiming)

    @Test
    fun editAllAtAMovedOccurrenceRenamesSeriesAndOccurrence() {
        val edited = weekly.copy(title = "Renamed", location = "Room B", timing = movedTiming)
        assertEquals(
            listOf(ProviderOp.UpdateEvent(masterRow, mapOf(C.TITLE to "Renamed")), ProviderOp.UpdateEvent(101, mapOf(C.TITLE to "Renamed"))),
            DeviceWritePlanner.editAll(stateOf(weekly, listOf(moved)), at(2026, 10, 19, 9), edited, alerts),
        )
    }

    @Test
    fun editFollowingFromAMovedOccurrenceKeepsTheSeriesTime() {
        val key = at(2026, 10, 19, 9)
        val plan = DeviceWritePlanner.editFollowing(stateOf(counted, listOf(moved)), key, counted.copy(title = "New", location = "Room B", timing = movedTiming), alerts)
        val insert = plan.ops.filterIsInstance<ProviderOp.InsertEvent>().single()
        assertEquals(key, insert.values[C.DTSTART])
        val followUp = plan.followUp(500).single() as ProviderOp.InsertException
        assertEquals(at(2026, 10, 19, 11), followUp.values[C.DTSTART])
    }

    @Test
    fun editAllWithANewRuleRemovesRowsItNoLongerProduces() {
        val key = at(2026, 10, 19, 9)
        val state = stateOf(weekly, listOf(exception(102, at(2026, 11, 2, 9), title = "Moved")))
        val edited = weekly.copy(rrule = "FREQ=WEEKLY;BYDAY=TU", timing = OccurrenceCalculator.timingAt(weekly.timing, key))
        val ops = DeviceWritePlanner.editAll(state, key, edited, alerts)
        assertTrue(ProviderOp.DeleteEvent(102) in ops)
        // The rule starts on its first Tuesday.
        val master = ops.filterIsInstance<ProviderOp.UpdateEvent>().single { it.id == masterRow }
        assertEquals(at(2026, 10, 6, 9), master.values[C.DTSTART])
    }

    @Test
    fun editAllEndingTheRuleLeavesTheDeletedRowsAlone() {
        val key = at(2026, 10, 19, 9)
        val state = stateOf(weekly, listOf(exception(103, key, title = "Own")))
        val edited = weekly.copy(rrule = null, title = "Single", timing = OccurrenceCalculator.timingAt(weekly.timing, key))
        val ops = DeviceWritePlanner.editAll(state, key, edited, alerts)
        assertTrue(ProviderOp.DeleteEvent(103) in ops)
        assertTrue(ops.none { it is ProviderOp.UpdateEvent && it.id == 103L })
    }

    @Test
    fun deleteAllDeletesTheMaster() {
        assertEquals(listOf(ProviderOp.DeleteEvent(masterRow)), DeviceWritePlanner.delete(stateOf(weekly)))
    }

    @Test
    fun forbiddenColumnsAreNeverWritten() {
        val forbidden = setOf("eventColor", "eventColor_index", "calendar_color", "_sync_id", "dirty", "visible", "uid2445", "hasAttendeeData", "organizer")
        val key = at(2026, 10, 19, 9)
        val edited = counted.copy(title = "X", colorKey = "rose", timing = OccurrenceCalculator.timingAt(counted.timing, key))
        val state = stateOf(counted, followingExceptions)
        val plans = listOf(
            DeviceWritePlanner.insert(edited, alerts),
            DeviceWritePlanner.update(state, edited.copy(rrule = null), alerts),
            DeviceWritePlanner.editThis(state, key, edited),
            DeviceWritePlanner.editFollowing(state, key, edited, alerts).let { it.ops + it.followUp(500) },
            DeviceWritePlanner.editAll(state, key, edited, alerts),
        ).flatten()
        val columns = plans.flatMap {
            when (it) {
                is ProviderOp.InsertEvent -> it.values.keys
                is ProviderOp.UpdateEvent -> it.values.keys
                is ProviderOp.InsertException -> it.values.keys
                else -> emptySet()
            }
        }.toSet()
        assertTrue(columns.isNotEmpty())
        assertTrue(columns.intersect(forbidden).isEmpty())
    }
}
