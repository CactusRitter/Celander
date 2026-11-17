package com.celander.app.core.device

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EventAccess
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.ReadOnlyReason
import com.celander.app.core.model.SeriesCapabilities
import com.celander.app.core.model.SourceIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class DeviceMappingTest {
    private val day = 86_400_000L
    private val berlin = ZoneId.of("Europe/Berlin")
    private val calendar = CalendarInfo(SourceIds.device(7), "Work", "blue", source = CalendarSource.DEVICE)
    private val colorKeyOf: (Int) -> String = { "c$it" }
    private lateinit var savedZone: TimeZone

    @Before
    fun saveZone() {
        savedZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(savedZone)
    }

    private fun utcMidnight(date: LocalDate) = date.toEpochDay() * day
    private fun millis(text: String, zone: ZoneId = berlin) = ZonedDateTime.of(LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    private fun instance(
        begin: Long?,
        end: Long?,
        allDay: Boolean = false,
        eventId: Long = 11,
        zone: String? = "Europe/Berlin",
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        originalAllDay: Int? = null,
        calendarId: Long? = 7,
    ) = InstanceRow(
        eventId = eventId,
        begin = begin,
        end = end,
        allDay = if (allDay) 1 else 0,
        title = "Standup",
        eventTimezone = zone,
        calendarId = calendarId,
        originalId = originalId,
        originalInstanceTime = originalInstanceTime,
        originalAllDay = originalAllDay,
        displayColor = 0x112233,
        calendarColor = 0x112233,
    )

    private fun master(
        rrule: String? = "FREQ=WEEKLY",
        rdate: String? = null,
        exrule: String? = null,
        exdate: String? = null,
        dtEnd: Long? = millis("2026-10-23T11:00"),
        duration: String? = null,
        allDay: Boolean = false,
        start: Long = millis("2026-10-23T10:00"),
    ) = EventRow(
        id = 11,
        calendarId = 7,
        title = "Standup",
        dtStart = start,
        dtEnd = dtEnd,
        duration = duration,
        allDay = if (allDay) 1 else 0,
        eventTimezone = "Europe/Berlin",
        rrule = rrule,
        rdate = rdate,
        exrule = exrule,
        exdate = exdate,
        organizer = "me@example.com",
        ownerAccount = "me@example.com",
        accessLevel = 700,
    )

    private fun load(row: EventRow, exceptions: List<ExceptionRow> = emptyList(), reminders: List<ReminderRow> = emptyList(), canWrite: Boolean = true) =
        DeviceMapping.event(row, exceptions, reminders, canWrite, colorKeyOf)!!

    @Test
    fun allDayInstancesUseUtcDaysInEveryDeviceZone() {
        val cases = listOf(
            LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 10, 25), // Berlin leaves DST that night
            LocalDate.of(2026, 3, 28) to LocalDate.of(2026, 3, 30), // spans the March change
            LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 1), // Los Angeles leaves DST
            LocalDate.of(2026, 12, 31) to LocalDate.of(2027, 1, 1),
        )
        for (zone in listOf("Europe/Berlin", "America/Los_Angeles", "Pacific/Kiritimati")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            for ((start, last) in cases) {
                val row = instance(utcMidnight(start), utcMidnight(last.plusDays(1)), allDay = true, zone = "UTC")
                val occurrence = DeviceMapping.occurrence(row, null, calendar, colorKeyOf)
                assertEquals("$zone $start", EventTiming.AllDay(start, last), occurrence.timing)
                assertEquals(start.toEpochDay(), occurrence.originalStart)
                assertTrue(occurrence.allDay)
            }
        }
    }

    @Test
    fun zeroLengthAllDayRowsCoverTheirStartDay() {
        val date = LocalDate.of(2026, 10, 23)
        val timing = DeviceMapping.timing(utcMidnight(date), utcMidnight(date), allDay = true, timeZone = "UTC")
        assertEquals(EventTiming.AllDay(date, date), timing)
    }

    @Test
    fun timedInstancesKeepTheirInstantsAndZone() {
        val begin = millis("2026-10-25T02:30")
        val occurrence = DeviceMapping.occurrence(instance(begin, begin + 3_600_000), null, calendar, colorKeyOf)
        assertEquals(EventTiming.Timed(Instant.ofEpochMilli(begin), Instant.ofEpochMilli(begin + 3_600_000), berlin), occurrence.timing)
        assertEquals(begin, occurrence.originalStart)
        assertEquals(SourceIds.device(11), occurrence.event.id)
        assertEquals(SourceIds.device(7), occurrence.event.calendarId)
        assertEquals("device:11", occurrence.event.uid)
        assertFalse(occurrence.isException)
    }

    @Test
    fun endBeforeBeginIsClamped() {
        val begin = millis("2026-10-23T10:00")
        val timing = DeviceMapping.occurrence(instance(begin, begin - 60_000), null, calendar, colorKeyOf).timing as EventTiming.Timed
        assertEquals(timing.start, timing.end)
        val allDay = DeviceMapping.timing(utcMidnight(LocalDate.of(2026, 10, 23)), 0, allDay = true, timeZone = null)
        assertEquals(EventTiming.AllDay(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23)), allDay)
    }

    @Test
    fun badOrMissingZonesFallBackToTheDeviceZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val begin = millis("2026-10-23T10:00")
        for (zone in listOf("Not/AZone", "", null)) {
            val timing = DeviceMapping.occurrence(instance(begin, begin, zone = zone), null, calendar, colorKeyOf).timing as EventTiming.Timed
            assertEquals(ZoneId.of("America/Los_Angeles"), timing.zone)
        }
    }

    @Test
    fun timedExceptionOfAnAllDaySeriesIsKeyedByDay() {
        val date = LocalDate.of(2026, 10, 23)
        val begin = millis("2026-10-23T15:00")
        val row = instance(begin, begin + 3_600_000, eventId = 99, originalId = 11, originalInstanceTime = utcMidnight(date), originalAllDay = 1)
        val occurrence = DeviceMapping.occurrence(row, "FREQ=DAILY", calendar, colorKeyOf)
        assertEquals(date.toEpochDay(), occurrence.originalStart)
        assertTrue(occurrence.timing is EventTiming.Timed)
        assertTrue(occurrence.isException)
        assertEquals(SourceIds.device(11), occurrence.event.id)
        assertEquals("FREQ=DAILY", occurrence.event.rrule)
        assertEquals("${SourceIds.device(11)}:${date.toEpochDay()}", occurrence.key)
    }

    @Test
    fun allDayExceptionOfATimedSeriesIsKeyedByMillis() {
        val original = millis("2026-10-23T10:00")
        val date = LocalDate.of(2026, 10, 24)
        val row = instance(utcMidnight(date), utcMidnight(date.plusDays(1)), allDay = true, eventId = 99, originalId = 11, originalInstanceTime = original, originalAllDay = 0)
        val occurrence = DeviceMapping.occurrence(row, "FREQ=WEEKLY", calendar, colorKeyOf)
        assertEquals(original, occurrence.originalStart)
        assertEquals(EventTiming.AllDay(date, date), occurrence.timing)
    }

    @Test
    fun badRowsAndUnknownCalendarsAreSkipped() {
        val begin = millis("2026-10-23T10:00")
        val rows = listOf(
            instance(begin, begin + 1),
            instance(null, begin),
            instance(begin, begin, calendarId = null),
            instance(begin, begin, calendarId = 8),
            instance(begin, begin, eventId = 12),
        )
        val result = DeviceMapping.occurrences(rows, mapOf(calendar.id to calendar), emptyMap(), colorKeyOf)
        assertEquals(listOf(SourceIds.device(11), SourceIds.device(12)), result.map { it.event.id })
    }

    @Test
    fun modifiedInstancesGetTheMasterRule() {
        val begin = millis("2026-10-23T10:00")
        val rows = listOf(instance(begin, begin, eventId = 99, originalId = 11, originalInstanceTime = begin, originalAllDay = 0))
        val result = DeviceMapping.occurrences(rows, mapOf(calendar.id to calendar), mapOf(11L to "FREQ=WEEKLY"), colorKeyOf)
        assertTrue(result.single().isRecurring)
    }

    @Test
    fun ownColorOnlyWhenItDiffersFromTheCalendar() {
        val begin = millis("2026-10-23T10:00")
        val same = DeviceMapping.occurrence(instance(begin, begin), null, calendar, colorKeyOf)
        assertNull(same.event.colorKey)
        assertEquals("blue", same.colorKey)
        val own = DeviceMapping.occurrence(instance(begin, begin).copy(displayColor = 0x445566), null, calendar, colorKeyOf)
        assertEquals("c${0x445566}", own.event.colorKey)
    }

    @Test
    fun masterEndComesFromDtEndOrDuration() {
        val start = millis("2026-10-23T10:00")
        fun end(row: EventRow) = (load(row).event.timing as EventTiming.Timed).end.toEpochMilli()
        assertEquals(millis("2026-10-23T11:00"), end(master()))
        assertEquals(start + Duration.ofMinutes(90).toMillis(), end(master(dtEnd = null, duration = "PT1H30M")))
        assertEquals(start + 3_600_000, end(master(dtEnd = null, duration = "P3600S")))
        assertEquals(start, end(master(dtEnd = null, duration = null)))
        assertEquals(start, end(master(dtEnd = null, duration = "garbage")))
        // Rows with both (written by sync adapters): the provider's instances take the duration, so the
        // detail sheet must too, or it shows another end than the day view.
        assertEquals(millis("2026-10-23T17:00"), end(master(dtEnd = millis("2026-10-23T19:00"), duration = "P25200S")))
        assertEquals(millis("2026-10-23T11:00"), end(master(duration = "garbage")))

        val date = LocalDate.of(2026, 10, 23)
        fun allDay(duration: String) = load(master(dtEnd = null, duration = duration, allDay = true, start = utcMidnight(date))).event.timing
        assertEquals(EventTiming.AllDay(date, date), allDay("P1D"))
        assertEquals(EventTiming.AllDay(date, date.plusDays(1)), allDay("P2D"))
        assertEquals(EventTiming.AllDay(date, date), allDay("P86400S"))
    }

    @Test
    fun providerDurationsWithoutTimeDesignator() {
        assertEquals(Duration.ofSeconds(3600), DeviceMapping.duration("P3600S"))
        assertEquals(Duration.ofMinutes(-15), DeviceMapping.duration("-P15M"))
        assertEquals(Duration.ofDays(14), DeviceMapping.duration("P2W"))
        assertEquals(Duration.ofMinutes(15), DeviceMapping.duration("PT15M"))
        assertEquals(Duration.ofDays(1).plusHours(2), DeviceMapping.duration("P1DT2H"))
        assertNull(DeviceMapping.duration("one hour"))
    }

    @Test
    fun masterFieldsRemindersAndUid() {
        val loaded = load(
            master().copy(uid = "abc@google.com", location = "Room 1"),
            reminders = listOf(ReminderRow(11, 30, 1), ReminderRow(11, 10, 0), ReminderRow(11, 30, 1), ReminderRow(11, 60, 2), ReminderRow(11, null, 1)),
        )
        assertEquals("abc@google.com", loaded.event.uid)
        assertEquals("Room 1", loaded.event.location)
        assertEquals(listOf(10, 30), loaded.event.reminders)
        assertEquals(SourceIds.device(11), loaded.event.id)
        assertEquals("device:11", load(master()).event.uid)
    }

    @Test
    fun deletedMastersAreMissing() {
        assertNull(DeviceMapping.event(master().copy(deleted = 1), emptyList(), emptyList(), true, colorKeyOf))
    }

    @Test
    fun exceptionsAreKeyedByTheirOriginalAllDayFlag() {
        val date = LocalDate.of(2026, 10, 30)
        val timedStart = millis("2026-10-30T15:00")
        val allDayMaster = master(allDay = true, start = utcMidnight(LocalDate.of(2026, 10, 23)), dtEnd = null, duration = "P1D")
        val loaded = load(
            allDayMaster,
            exceptions = listOf(
                ExceptionRow(21, utcMidnight(date), originalAllDay = 1, dtStart = timedStart, dtEnd = timedStart + 3_600_000, eventTimezone = "Europe/Berlin", title = "Moved"),
                ExceptionRow(22, utcMidnight(date.plusDays(7)), originalAllDay = 1, status = 2),
                ExceptionRow(23, utcMidnight(date.plusDays(14)), originalAllDay = 1, deleted = 1),
                ExceptionRow(24, null, originalAllDay = 1),
                ExceptionRow(25, utcMidnight(date.plusDays(21)), originalAllDay = null, status = 2),
            ),
        )
        assertEquals(listOf(SourceIds.device(21), SourceIds.device(22), SourceIds.device(25)), loaded.exceptions.map { it.id })
        val moved = loaded.exceptions[0]
        assertEquals(date.toEpochDay(), moved.originalStart)
        assertEquals(SourceIds.device(11), moved.eventId)
        assertEquals("Moved", moved.title)
        assertFalse(moved.cancelled)
        assertEquals(EventTiming.Timed(Instant.ofEpochMilli(timedStart), Instant.ofEpochMilli(timedStart + 3_600_000), berlin), moved.timing)
        assertTrue(loaded.exceptions[1].cancelled)
        assertEquals(date.plusDays(7).toEpochDay(), loaded.exceptions[1].originalStart)
        assertEquals(date.plusDays(21).toEpochDay(), loaded.exceptions[2].originalStart)

        val timedOriginal = millis("2026-10-30T10:00")
        val reverse = load(
            master(),
            exceptions = listOf(ExceptionRow(31, timedOriginal, originalAllDay = 0, dtStart = utcMidnight(date), dtEnd = utcMidnight(date.plusDays(1)), allDay = 1)),
        ).exceptions.single()
        assertEquals(timedOriginal, reverse.originalStart)
        assertEquals(EventTiming.AllDay(date, date), reverse.timing)
    }

    @Test
    fun accessMatrix() {
        data class Case(
            val canWrite: Boolean,
            val level: Int,
            val isOrganizer: Int?,
            val organizer: String?,
            val hasAttendees: Int?,
            val guestsCanModify: Int?,
            val expected: EventAccess,
        )
        val me = "me@example.com"
        val boss = "boss@example.com"
        val cases = listOf(
            Case(true, 700, 1, me, 1, 0, EventAccess(true, true, null, me)),
            Case(true, 700, null, "ME@example.com", 1, 0, EventAccess(true, true, null, "ME@example.com")),
            Case(true, 700, null, boss, 1, 0, EventAccess(false, false, ReadOnlyReason.GUEST, boss)),
            Case(true, 700, null, boss, 1, 1, EventAccess(true, false, ReadOnlyReason.GUEST, boss)),
            Case(true, 700, 0, me, 1, 0, EventAccess(false, false, ReadOnlyReason.GUEST, me)),
            Case(true, 700, 0, boss, 0, 0, EventAccess(true, true, null, boss)),
            Case(true, 700, null, null, 1, 0, EventAccess(false, false, ReadOnlyReason.GUEST, null)),
            Case(true, 500, 1, me, 1, 0, EventAccess(true, true, null, me)),
            Case(true, 200, 1, me, 1, 0, EventAccess(false, false, ReadOnlyReason.CALENDAR, me)),
            Case(true, 200, null, boss, 1, 1, EventAccess(false, false, ReadOnlyReason.CALENDAR, boss)),
            Case(false, 700, 1, me, 1, 0, EventAccess(false, false, ReadOnlyReason.NO_PERMISSION, me)),
            Case(false, 200, 0, boss, 1, 1, EventAccess(false, false, ReadOnlyReason.NO_PERMISSION, boss)),
        )
        for (c in cases) {
            val row = master().copy(accessLevel = c.level, isOrganizer = c.isOrganizer, organizer = c.organizer, hasAttendeeData = c.hasAttendees, guestsCanModify = c.guestsCanModify)
            assertEquals(c.toString(), c.expected, DeviceMapping.access(row, c.canWrite))
            assertEquals(c.expected, load(row, canWrite = c.canWrite).access)
        }
    }

    @Test
    fun capabilityMatrix() {
        val modified = ExceptionRow(21, millis("2026-10-30T10:00"), originalAllDay = 0, dtStart = millis("2026-10-30T12:00"), dtEnd = millis("2026-10-30T13:00"))
        val cancelled = ExceptionRow(22, millis("2026-11-06T10:00"), originalAllDay = 0, status = 2)
        val cases = listOf(
            master() to SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = true),
            master(rrule = null) to SeriesCapabilities(canSplit = false, canCap = false, canShiftAll = false),
            master(rrule = "NONSENSE") to SeriesCapabilities(canSplit = false, canCap = false, canShiftAll = false),
            master(rdate = "20261101T090000Z") to SeriesCapabilities(canSplit = false, canCap = false, canShiftAll = false),
            master(exrule = "FREQ=MONTHLY") to SeriesCapabilities(canSplit = false, canCap = true, canShiftAll = false),
            master(exdate = "20261030T080000Z") to SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = false),
            master(exdate = "Europe/Berlin;20261030T100000") to SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = false),
            master(exdate = "Mars/Olympus;20261030T100000") to SeriesCapabilities(canSplit = false, canCap = true, canShiftAll = false),
            master(exdate = "garbage") to SeriesCapabilities(canSplit = false, canCap = true, canShiftAll = false),
            master(rrule = "FREQ=YEARLY;COUNT=5;BYWEEKNO=20") to SeriesCapabilities(canSplit = false, canCap = true, canShiftAll = false),
            master(rrule = "FREQ=YEARLY;UNTIL=20301231T000000Z;BYWEEKNO=20") to SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = true),
            master(rrule = "FREQ=WEEKLY;COUNT=5") to SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = true),
        )
        for ((row, expected) in cases) assertEquals(row.toString(), expected, load(row).capabilities)

        assertEquals(SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = true), load(master(), listOf(cancelled)).capabilities)
        assertEquals(SeriesCapabilities(canSplit = true, canCap = true, canShiftAll = false), load(master(), listOf(cancelled, modified)).capabilities)
    }

    @Test
    fun calendarRowsBecomeDeviceCalendars() {
        val row = CalendarRow(
            id = 7,
            accountName = "me@gmail.com",
            accountType = "com.google",
            syncId = "me@gmail.com",
            name = "me@gmail.com",
            displayName = "Personal",
            color = 0x3473DB,
            accessLevel = 700,
            visible = 1,
            maxReminders = 5,
        )
        val info = DeviceMapping.calendar(row, canWrite = true, prefVisible = null, prefColorKey = null, colorKeyOf = colorKeyOf)
        assertEquals(
            CalendarInfo(SourceIds.device(7), "Personal", "c${0x3473DB}", visible = true, source = CalendarSource.DEVICE, writable = true, account = "me@gmail.com", accountType = "com.google", maxReminders = 5),
            info,
        )
        val overridden = DeviceMapping.calendar(row.copy(displayName = null), canWrite = true, prefVisible = false, prefColorKey = "teal", colorKeyOf = colorKeyOf)
        assertEquals("me@gmail.com", overridden.name)
        assertFalse(overridden.visible)
        assertEquals("teal", overridden.colorKey)
        assertFalse(DeviceMapping.calendar(row, canWrite = false, prefVisible = null, prefColorKey = null, colorKeyOf = colorKeyOf).writable)
        assertFalse(DeviceMapping.calendar(row.copy(accessLevel = 200), canWrite = true, prefVisible = null, prefColorKey = null, colorKeyOf = colorKeyOf).writable)
        assertFalse(DeviceMapping.calendar(row.copy(visible = 0), canWrite = true, prefVisible = null, prefColorKey = null, colorKeyOf = colorKeyOf).visible)
    }

    @Test
    fun stableKeysPreferSyncIdThenNameThenId() {
        val row = CalendarRow(id = 7, accountName = "me@gmail.com", accountType = "com.google", syncId = "sync", name = "name")
        assertEquals("com.google|me@gmail.com|sync", DeviceMapping.stableKey(row))
        assertEquals("com.google|me@gmail.com|name", DeviceMapping.stableKey(row.copy(syncId = null)))
        assertEquals("com.google|me@gmail.com|id:7", DeviceMapping.stableKey(row.copy(syncId = null, name = null)))
    }

    @Test
    fun calendarsSortByPrimaryAccountThenPrimaryThenName() {
        val rows = listOf(
            CalendarRow(1, accountName = "local", displayName = "A local"),
            CalendarRow(2, accountName = "b@gmail.com", displayName = "Zeta"),
            CalendarRow(3, accountName = "b@gmail.com", displayName = "b@gmail.com", isPrimary = 1),
            CalendarRow(4, accountName = "a@gmail.com", displayName = "Holidays"),
            CalendarRow(5, accountName = "a@gmail.com", displayName = "a@gmail.com", isPrimary = 1),
            CalendarRow(6, accountName = "b@gmail.com", displayName = "alpha"),
        )
        assertEquals(listOf(5L, 4L, 3L, 6L, 2L, 1L), DeviceMapping.sorted(rows).map { it.id })
    }
}
